package org.benf.cfr.reader.bytecode.analysis.parse.pattern;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.Pattern;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.misc.Precedence;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.AbstractLValue;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.CloneHelper;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriterFlags;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueAssignmentCollector;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifierFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.bytecode.analysis.types.discovery.InferredJavaType;
import org.benf.cfr.reader.entities.ClassFileField;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.StringUtils;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;

public class RecordPattern implements Pattern {
    LValue outer;
    List<LValue> params;

    public RecordPattern(LValue outer, List<LValue> params) {
        this.outer = outer;
        this.params = params;
    }

    @Override
    public InferredJavaType getInferredJavaType() {
        return outer.getInferredJavaType();
    }

    @Override
    public Dumper dump(Dumper d, boolean defines) {
        d.dump(outer.getInferredJavaType().getJavaTypeInstance());
        d.print('(');
        boolean first = true;
        for (LValue lv : params) {
            first = StringUtils.comma(first, d);
            if (lv instanceof RecordPatternPlaceholder) {
                d.print("_");
            } else {
                LValue.Creation.dump(d, lv);
            }
        }
        d.print(')');
        return d;
    }

    @Override
    public Dumper dump(Dumper d) {
        return dump(d, true);
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        collector.collectFrom(outer);
        for (LValue lv : params) {
            collector.collectFrom(lv);
        }
    }

    public static class RecordPatternPlaceholder extends AbstractLValue {

        public RecordPatternPlaceholder(InferredJavaType inferredJavaType) {
            super(inferredJavaType);
        }

        @Override
        public Dumper dumpInner(Dumper d) {
            return d;
        }

        @Override
        public Precedence getPrecedence() {
            return Precedence.WEAKEST;
        }

        @Override
        public int getNumberOfCreators() {
            return 0;
        }

        @Override
        public <T> void collectLValueAssignments(Expression assignedTo, StatementContainer<T> statementContainer, LValueAssignmentCollector<T> lValueAssigmentCollector) {

        }

        @Override
        public SSAIdentifiers<LValue> collectVariableMutation(SSAIdentifierFactory<LValue, ?> ssaIdentifierFactory) {
            return null;
        }

        @Override
        public LValue replaceSingleUsageLValues(LValueRewriter lValueRewriter, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer) {
            return this;
        }

        @Override
        public LValue applyExpressionRewriter(ExpressionRewriter expressionRewriter, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            return this;
        }

        @Override
        public void markFinal() {

        }

        @Override
        public boolean isFinal() {
            return false;
        }

        @Override
        public void markVar() {

        }

        @Override
        public boolean isVar() {
            return false;
        }

        @Override
        public LValue deepClone(CloneHelper cloneHelper) {
            return this;
        }
    }
}
