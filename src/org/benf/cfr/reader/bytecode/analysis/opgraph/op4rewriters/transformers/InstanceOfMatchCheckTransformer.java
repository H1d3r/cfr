package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers;

import org.benf.cfr.reader.bytecode.analysis.loc.BytecodeLoc;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.AssignmentExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BoolOp;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BooleanExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BooleanOperation;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.CastExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.CompOp;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ComparisonOperation;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConditionalExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.InstanceOfExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.InstanceOfExpressionDefining;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.Literal;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.LValueExpression;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.Block;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredAssignment;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredIf;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredWhile;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.collections.SetFactory;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/*
 * Cleanup pass for the j18+ instanceof pattern shape that op03's
 * condenseInstanceOfAssign and scope discovery's j16 lift produce together.
 *
 * We absorb every cast-assign into its preceding if's condition with the
 * synthetic null-check trick:
 *
 *   if (!(o instanceof T)) goto E;          \
 *   T b = (T)o;                              >  ==>  if (!(o instanceof T && null != (b = (T)o))) goto E;
 *   <continuation>                          /        <continuation>
 *
 * Then scope discovery, via InstanceOfAssignRewriter's MatchType.SIMPLE_J16,
 * tries to lift the bound variable into the instanceof:
 *   - SUCCESS (b is a fresh local): condition becomes
 *       instanceof T b && null != b
 *     The null-check is now redundant — instanceof T b implies b != null —
 *     but no earlier pass elides it.
 *   - REFUSAL (b is method-scoped, used outside the if): condition stays
 *       instanceof T && null != (b = (T)o)
 *     The original pre-pattern code was nicer than this absorbed form.
 *
 * This pass walks each StructuredIf's top-level AND chain and:
 *
 *   (1) Drops `null != b` operands (in that exact order — the canary shape
 *       this pipeline synthesises) when a sibling defines b via
 *       `instanceof T b`.
 *   (2) Pushes a *trailing* `null != (b = (T)o)` operand down into the body
 *       as an assignment statement, when no sibling defines b. Only the LAST
 *       operand qualifies — see correctness note below.
 *
 * Correctness note on the push-down:
 *   Original: instanceof T && null != (b = (T)o) && cond2
 *     - instanceof T true, cond2 false → the && evaluates left-to-right, the
 *       assignment side-effect of the middle clause runs BEFORE cond2 is
 *       checked, so b IS assigned even when the if isn't taken.
 *   Naive push-down to: if (instanceof T && cond2) { b = (T)o; ... }
 *     - cond2 false → body not entered → b NOT assigned. Differs.
 *   For a method-scoped b that's read after the if, this is observable.
 *   Therefore push the assignment down only if it is the LAST operand, where
 *   no later condition can short-circuit and the side effect would have been
 *   observable only on the if-taken path anyway.
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
            tidy((StructuredIf) in);
        } else if (in instanceof StructuredWhile) {
            tidyWhile((StructuredWhile) in);
        }
        return in;
    }

    /*
     * Loop variant: only the redundant-null-check drop (1). The push-down (2) is
     * deliberately NOT applied to loops - it prepends the absorbed assignment into
     * the body, but a loop body runs every iteration whereas the condition's
     * side-effecting assignment runs every test, so pushing it down would change
     * semantics. Dropping `null != t` where a sibling `instanceof T t` defines t is
     * always safe (the pattern match proves t non-null).
     */
    private static void tidyWhile(StructuredWhile sw) {
        ConditionalExpression cond = sw.getCondition();
        if (cond == null) return;
        ConditionalExpression rebuilt = dropRedundantNullChecks(cond);
        if (rebuilt != cond) sw.setCondition(rebuilt);
    }

    private static ConditionalExpression dropRedundantNullChecks(ConditionalExpression cond) {
        List<ConditionalExpression> operands = ListFactory.newList();
        flattenAnd(cond, operands);
        if (operands.size() <= 1) return cond;

        Set<LValue> defined = SetFactory.newSet();
        for (ConditionalExpression op : operands) {
            LValue d = getDefinedLValue(op);
            if (d != null) defined.add(d);
        }

        boolean changed = false;
        Iterator<ConditionalExpression> it = operands.iterator();
        while (it.hasNext()) {
            LValue v = getNullCheckedLValue(it.next());
            if (v != null && defined.contains(v)) {
                it.remove();
                changed = true;
            }
        }
        if (!changed || operands.isEmpty()) return cond;

        ConditionalExpression rebuilt = operands.get(0);
        for (int i = 1; i < operands.size(); i++) {
            rebuilt = new BooleanOperation(BytecodeLoc.NONE, rebuilt, operands.get(i), BoolOp.AND);
        }
        return rebuilt;
    }

    private static void tidy(StructuredIf sif) {
        ConditionalExpression cond = sif.getConditionalExpression();
        List<ConditionalExpression> operands = ListFactory.newList();
        flattenAnd(cond, operands);
        if (operands.size() <= 1) return;

        Set<LValue> defined = SetFactory.newSet();
        for (ConditionalExpression op : operands) {
            LValue d = getDefinedLValue(op);
            if (d != null) defined.add(d);
        }

        boolean changed = false;
        AssignmentExpression pushDown = null;

        // (2) Push-down candidate: only the LAST operand, only if it's our
        // canary's full shape — `null != (b = (T)o)` paired with a plain
        // `instanceof T` sibling whose subject and type match the cast.
        // The sibling-match is what makes the rewrite semantics-preserving:
        // with `instanceof T` true on the if-taken path, `(T)o` is provably
        // non-null, so the null-check it carries is dead weight. Without
        // that pairing (e.g. a user-written `null != (b = somefunc())`), the
        // null-check is doing real work and we must NOT push it down — see
        // InstanceOfPatternTest20 for the negative case.
        ConditionalExpression last = operands.get(operands.size() - 1);
        AssignmentExpression absorbed = getAbsorbedAssignment(last);
        if (absorbed != null
                && !defined.contains(absorbed.getlValue())
                && hasMatchingInstanceofSibling(absorbed, operands)) {
            pushDown = absorbed;
            operands.remove(operands.size() - 1);
            changed = true;
        }

        // (1) Drop redundant null-checks of variables bound by a sibling defining instanceof.
        Iterator<ConditionalExpression> it = operands.iterator();
        while (it.hasNext()) {
            LValue v = getNullCheckedLValue(it.next());
            if (v != null && defined.contains(v)) {
                it.remove();
                changed = true;
            }
        }

        if (!changed) return;
        if (operands.isEmpty()) return; // defensive — shouldn't happen with well-formed input

        ConditionalExpression rebuilt = operands.get(0);
        for (int i = 1; i < operands.size(); i++) {
            rebuilt = new BooleanOperation(BytecodeLoc.NONE, rebuilt, operands.get(i), BoolOp.AND);
        }
        sif.setConditionalExpression(rebuilt);

        if (pushDown != null) {
            prependAssignment(sif, pushDown);
        }
    }

    /*
     * Add the assignment to the start of the block inside the conditional.
     */
    private static void prependAssignment(StructuredIf sif, AssignmentExpression assign) {
        StructuredAssignment newAssign = new StructuredAssignment(
                BytecodeLoc.NONE, assign.getlValue(), assign.getrValue(), false);
        Op04StructuredStatement newAssignContainer = new Op04StructuredStatement(newAssign);

        Op04StructuredStatement ifTaken = sif.getIfTaken();
        StructuredStatement body = ifTaken.getStatement();
        if (body instanceof Block) {
            ((Block) body).getBlockStatements().add(0, newAssignContainer);
        } else {
            LinkedList<Op04StructuredStatement> kids = new LinkedList<Op04StructuredStatement>();
            kids.add(newAssignContainer);
            kids.add(new Op04StructuredStatement(body));
            ifTaken.replaceStatement(new Block(kids, true));
        }
    }

    // Todo : This feels like it should be utility elsewhere.
    private static void flattenAnd(ConditionalExpression e, List<ConditionalExpression> out) {
        if (e instanceof BooleanOperation && ((BooleanOperation) e).getOp() == BoolOp.AND) {
            BooleanOperation bo = (BooleanOperation) e;
            flattenAnd(bo.getLhs(), out);
            flattenAnd(bo.getRhs(), out);
            return;
        }
        out.add(e);
    }

    /*
     * InstanceOfExpressionDefining extends AbstractExpression but does NOT
     * implement ConditionalExpression — it must be wrapped in BooleanExpression
     * to appear inside a boolean condition. Since `op` is typed as a
     * ConditionalExpression, the only way to see an InstanceOfExpressionDefining
     * here is through that wrap. ComparisonOperation by contrast already
     * implements ConditionalExpression directly, so the matchers below don't
     * need to unwrap.
     */
    private static LValue getDefinedLValue(ConditionalExpression op) {
        if (op instanceof InstanceOfExpressionDefining) {
            return ((InstanceOfExpressionDefining) op).getDefines();
        }
        return null;
    }

    /*
     * Recognise only the exact canary shape introduced by condenseInstanceOfAssign
     * (Literal.NULL on the LHS, target expression on the RHS, op NE).
     * Returns the RHS, or null if `op` is not the canary shape.
     */
    private static Expression getCanaryRhs(ConditionalExpression op) {
        if (!(op instanceof ComparisonOperation)) return null;
        ComparisonOperation cmp = (ComparisonOperation) op;
        if (cmp.getOp() != CompOp.NE) return null;
        if (!Literal.NULL.equals(cmp.getLhs())) return null;
        return cmp.getRhs();
    }

    /*
     * `null != b` — the post-j16-lift residue, redundant given a sibling
     * `instanceof T b` clause.
     */
    private static LValue getNullCheckedLValue(ConditionalExpression op) {
        Expression rhs = getCanaryRhs(op);
        if (!(rhs instanceof LValueExpression)) return null;
        return ((LValueExpression) rhs).getLValue();
    }

    /*
     * `null != (b = (T)o)` — the assignment still embedded in the canary
     * because the j16 lift refused (e.g. b is method-scoped).
     */
    private static AssignmentExpression getAbsorbedAssignment(ConditionalExpression op) {
        Expression rhs = getCanaryRhs(op);
        return rhs instanceof AssignmentExpression ? (AssignmentExpression) rhs : null;
    }

    /*
     * The canary always pairs the absorbed `null != (b = (T)o)` with a plain
     * `instanceof T` whose subject and type match the cast. We require that
     * pairing to be confident this clause came from condenseInstanceOfAssign
     * rather than user code where the null-check carries semantic weight.
     */
    private static boolean hasMatchingInstanceofSibling(AssignmentExpression absorbed, List<ConditionalExpression> operands) {
        if (!(absorbed.getrValue() instanceof CastExpression)) return false;
        CastExpression cast = (CastExpression) absorbed.getrValue();
        Expression castSubject = cast.getChild();
        JavaTypeInstance castType = cast.getInferredJavaType().getJavaTypeInstance();
        for (ConditionalExpression op : operands) {
            if (!(op instanceof InstanceOfExpression)) continue;
            InstanceOfExpression ioe = (InstanceOfExpression) op;
            if (!ioe.getLhs().equals(castSubject)) continue;
            if (!ioe.getTypeInstance().equals(castType)) continue;
            return true;
        }
        return false;
    }
}
