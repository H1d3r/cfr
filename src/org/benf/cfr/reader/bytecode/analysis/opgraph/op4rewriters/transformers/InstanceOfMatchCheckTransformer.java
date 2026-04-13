package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.*;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.AbstractExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriterFlags;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredIf;

/*
 * prior to j18, x instanceof Foo z was .... downright ugly, but very visible.
18+
{
    if (obj instanceof org.benf.cfr.tests.InstanceOfPatternTest1$Person) {
        var1_1 = ((org.benf.cfr.tests.InstanceOfPatternTest1$Person)obj).getAge();
    }
    if (obj instanceof org.benf.cfr.tests.InstanceOfPatternTest1$Person) {
        person1 = (org.benf.cfr.tests.InstanceOfPatternTest1$Person)obj;
        age = person1.getAge();
        java.lang.System.out.println("Age is " + age);
    }
}
16
{
    if ((var2_3 = obj) instanceof org.benf.cfr.tests.InstanceOfPatternTest1$Person && (person1 = (org.benf.cfr.tests.InstanceOfPatternTest1$Person)var2_3) == (org.benf.cfr.tests.InstanceOfPatternTest1$Person)var2_3) {
        age = person1.getAge();
        java.lang.System.out.println("Age is " + age);
    }
}
 * Now, we need a post hoc lift.
 *
 */
public class InstanceOfMatchCheckTransformer implements StructuredStatementTransformer {

    public void transform(Op04StructuredStatement root) {
        StructuredScope structuredScope = new StructuredScope();
        root.transform(this, structuredScope);
    }

    /*
     * Very simple check - conditional with immediate declaration.
     */
    @Override
    public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
        in.transformStructuredChildren(this, scope);
        if (in instanceof StructuredIf) {
            ((StructuredIf) in).getIfTaken();
        }
        return in;
    }

}
