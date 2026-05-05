package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.ExpressionReplacingRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BoolOp;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BooleanExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BooleanOperation;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.CastExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConditionalExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.InstanceOfExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.InstanceOfExpressionDefining;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.Block;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredAssignment;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredIf;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;

import java.util.List;

/*
 * Lift the j18+ post-pattern-matching shape:
 *
 *   if (obj instanceof T) {
 *       T s = (T) obj;
 *       ...
 *   }
 *
 * into the pattern form:
 *
 *   if (obj instanceof T s) {
 *       ...
 *   }
 *
 * The j14/j16 contrived shape (cast+assign folded inside the condition via a
 * self-comparison) is handled by InstanceOfAssignRewriter at scope-discovery
 * time. j18+ emits the straightforward shape above instead, so the lift has
 * to happen post-hoc here.
 *
 * Also completes the compound case set up by the op03 condense pass that
 * turns `instanceof T s && cond` j21 bytecode into:
 *
 *   if (obj instanceof T && cond) {
 *       s = (T) obj;
 *       ...
 *   }
 *
 * by collapsing the leading assignment into an instanceof-defining pattern.
 */
public class InstanceOfMatchCheckTransformer implements StructuredStatementTransformer {

    public void transform(Op04StructuredStatement root) {
        StructuredScope structuredScope = new StructuredScope();
        root.transform(this, structuredScope);
    }

    @Override
    public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
        in.transformStructuredChildren(this, scope);
        if (in instanceof StructuredIf) {
            tryLift((StructuredIf) in);
        }
        return in;
    }

    private static void tryLift(StructuredIf sif) {
        Op04StructuredStatement firstContainer = firstStatementOf(sif.getIfTaken());
        if (firstContainer == null) return;
        StructuredStatement first = firstContainer.getStatement();
        if (!(first instanceof StructuredAssignment)) return;
        StructuredAssignment assign = (StructuredAssignment) first;

        // Lift only when the assignment IS the variable's creation point.
        LValue lvalue = assign.getLvalue();
        if (!assign.isCreator(lvalue)) return;

        if (!(assign.getRvalue() instanceof CastExpression)) return;
        CastExpression cast = (CastExpression) assign.getRvalue();
        Expression castSubject = cast.getChild();
        JavaTypeInstance castType = cast.getInferredJavaType().getJavaTypeInstance();

        InstanceOfExpression target = findReachable(sif.getConditionalExpression(), castSubject, castType);
        if (target == null) return;

        InstanceOfExpressionDefining replacement = new InstanceOfExpressionDefining(
                target.getLoc(),
                target.getInferredJavaType(),
                target.getLhs(),
                castType,
                lvalue
        );

        sif.rewriteExpressions(new ExpressionReplacingRewriter(target, replacement));
        firstContainer.nopOut();
    }

    private static Op04StructuredStatement firstStatementOf(Op04StructuredStatement s) {
        StructuredStatement stmt = s.getStatement();
        if (stmt instanceof Block) {
            List<Op04StructuredStatement> bs = ((Block) stmt).getBlockStatements();
            if (bs.isEmpty()) return null;
            return bs.get(0);
        }
        return s;
    }

    /*
     * Find an InstanceOfExpression matching (subject, type) reachable from the
     * top of `cond` purely via &&-chain (and BooleanExpression wrapping). An
     * instanceof to the rhs of a disjunction, or inside a not doesn't positively assign
     * the then branch (though a 'not' can assign the else branch (!))
     */
    private static InstanceOfExpression findReachable(Expression cond, Expression subject, JavaTypeInstance type) {
        if (cond instanceof BooleanExpression) {
            return findReachable(((BooleanExpression) cond).getInner(), subject, type);
        }
        if (cond instanceof InstanceOfExpression) {
            InstanceOfExpression ioe = (InstanceOfExpression) cond;
            if (ioe.getLhs().equals(subject) && ioe.getTypeInstance().equals(type)) {
                return ioe;
            }
            return null;
        }
        if (cond instanceof BooleanOperation) {
            BooleanOperation bo = (BooleanOperation) cond;
            if (bo.getOp() != BoolOp.AND) return null;
            InstanceOfExpression r = findReachable(bo.getLhs(), subject, type);
            if (r != null) return r;
            return findReachable(bo.getRhs(), subject, type);
        }
        return null;
    }

}
