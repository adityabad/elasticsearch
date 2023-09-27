/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.conditional;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.EvalOperator.ExpressionEvaluator;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.evaluator.mapper.EvaluatorMapper;
import org.elasticsearch.xpack.esql.planner.LocalExecutionPlanner;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.expression.Nullability;
import org.elasticsearch.xpack.ql.expression.TypeResolutions;
import org.elasticsearch.xpack.ql.expression.function.scalar.ScalarFunction;
import org.elasticsearch.xpack.ql.expression.gen.script.ScriptTemplate;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.Source;
import org.elasticsearch.xpack.ql.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.elasticsearch.common.logging.LoggerMessageFormat.format;
import static org.elasticsearch.xpack.ql.type.DataTypes.NULL;

public class Case extends ScalarFunction implements EvaluatorMapper {
    record Condition(Expression condition, Expression value) {}

    private final List<Condition> conditions;
    private final Expression elseValue;
    private DataType dataType;

    @SuppressWarnings("this-escape")
    public Case(Source source, Expression first, List<Expression> rest) {
        super(source, Stream.concat(Stream.of(first), rest.stream()).toList());
        int conditionCount = children().size() / 2;
        conditions = new ArrayList<>(conditionCount);
        for (int c = 0; c < conditionCount; c++) {
            conditions.add(new Condition(children().get(c * 2), children().get(c * 2 + 1)));
        }
        elseValue = children().size() % 2 == 0 ? new Literal(source, null, NULL) : children().get(children().size() - 1);
    }

    @Override
    public DataType dataType() {
        if (dataType == null) {
            resolveType();
        }
        return dataType;
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new TypeResolution("Unresolved children");
        }

        if (children().size() < 2) {
            return new TypeResolution(format(null, "expected at least two arguments in [{}] but got {}", sourceText(), children().size()));
        }

        for (int c = 0; c < conditions.size(); c++) {
            Condition condition = conditions.get(c);

            TypeResolution resolution = TypeResolutions.isBoolean(
                condition.condition,
                sourceText(),
                TypeResolutions.ParamOrdinal.fromIndex(c * 2)
            );
            if (resolution.unresolved()) {
                return resolution;
            }

            resolution = resolveValueType(condition.value, c * 2 + 1);
            if (resolution.unresolved()) {
                return resolution;
            }
        }

        return resolveValueType(elseValue, conditions.size() * 2);
    }

    private TypeResolution resolveValueType(Expression value, int position) {
        if (dataType == null || dataType == NULL) {
            dataType = value.dataType();
            return TypeResolution.TYPE_RESOLVED;
        }
        return TypeResolutions.isType(
            value,
            t -> t == dataType,
            sourceText(),
            TypeResolutions.ParamOrdinal.fromIndex(position),
            dataType.typeName()
        );
    }

    @Override
    public Nullability nullable() {
        return Nullability.UNKNOWN;
    }

    @Override
    public ScriptTemplate asScript() {
        throw new UnsupportedOperationException("functions do not support scripting");
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new Case(source(), newChildren.get(0), newChildren.subList(1, newChildren.size()));
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, Case::new, children().get(0), children().subList(1, children().size()));
    }

    @Override
    public boolean foldable() {
        for (Condition condition : conditions) {
            if (condition.condition.foldable() == false) {
                return false;
            }
            Boolean b = (Boolean) condition.condition.fold();
            if (b != null && b) {
                return condition.value.foldable();
            }
        }
        return elseValue.foldable();
    }

    @Override
    public Object fold() {
        // TODO can we partially fold? like CASE(false, foo, bar) -> bar
        for (Condition condition : conditions) {
            Boolean b = (Boolean) condition.condition.fold();
            if (b != null && b) {
                return condition.value.fold();
            }
        }
        return elseValue.fold();
    }

    @Override
    public ExpressionEvaluator.Factory toEvaluator(Function<Expression, ExpressionEvaluator.Factory> toEvaluator) {

        List<ConditionEvaluatorSupplier> conditionsEval = conditions.stream()
            .map(c -> new ConditionEvaluatorSupplier(toEvaluator.apply(c.condition), toEvaluator.apply(c.value)))
            .toList();
        var elseValueEval = toEvaluator.apply(elseValue);
        return dvrCtx -> new CaseEvaluator(
            LocalExecutionPlanner.toElementType(dataType()),
            conditionsEval.stream().map(x -> x.apply(dvrCtx)).toList(),
            elseValueEval.get(dvrCtx)
        );
    }

    record ConditionEvaluatorSupplier(ExpressionEvaluator.Factory condition, ExpressionEvaluator.Factory value)
        implements
            Function<DriverContext, ConditionEvaluator> {
        @Override
        public ConditionEvaluator apply(DriverContext driverContext) {
            return new ConditionEvaluator(condition.get(driverContext), value.get(driverContext));
        }
    }

    record ConditionEvaluator(EvalOperator.ExpressionEvaluator condition, EvalOperator.ExpressionEvaluator value) implements Releasable {
        @Override
        public void close() {
            Releasables.closeExpectNoException(condition, value);
        }
    }

    private record CaseEvaluator(ElementType resultType, List<ConditionEvaluator> conditions, EvalOperator.ExpressionEvaluator elseVal)
        implements
            EvalOperator.ExpressionEvaluator {
        @Override
        public Block eval(Page page) {
            /*
             * We have to evaluate lazily so any errors or warnings that would be
             * produced by the right hand side are avoided. And so if anything
             * on the right hand side is slow we skip it.
             *
             * And it'd be good if that lazy evaluation were fast. But this
             * implementation isn't. It's fairly simple - running position at
             * a time - but it's not at all fast.
             */
            int positionCount = page.getPositionCount();
            Block.Builder result = resultType.newBlockBuilder(positionCount);
            position: for (int p = 0; p < positionCount; p++) {
                int[] positions = new int[] { p };
                Page limited = new Page(
                    IntStream.range(0, page.getBlockCount()).mapToObj(b -> page.getBlock(b).filter(positions)).toArray(Block[]::new)
                );
                for (ConditionEvaluator condition : conditions) {
                    Block e = condition.condition.eval(limited);
                    if (e.areAllValuesNull()) {
                        continue;
                    }
                    BooleanBlock b = (BooleanBlock) e;
                    if (b.isNull(0)) {
                        continue;
                    }
                    if (false == b.getBoolean(b.getFirstValueIndex(0))) {
                        continue;
                    }
                    result.copyFrom(condition.value.eval(limited), 0, 1);
                    continue position;
                }
                result.copyFrom(elseVal.eval(limited), 0, 1);
            }
            return result.build();
        }

        @Override
        public void close() {
            Releasables.closeExpectNoException(() -> Releasables.close(conditions), elseVal);
        }
    }
}