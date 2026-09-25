package io.github.markpollack.workflow.flows.compiler;

import java.util.List;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/** One allocation rule for analysis, lowering and later invocation-coordinate binding. */
final class Coordinates {
    static final String SCHEME="structured-placement-v1";
    private Coordinates() {}
    static Placement root(Definition<?,?> d) { return new Placement(List.of(new Segment("workflow",d.name(),0))); }
    static Placement node(Placement parent, Node node, int ordinal) { return parent.child("node",name(node),ordinal); }
    static String name(Node node) {
        return switch(node) {
            case Call n->n.id(); case Choice n->n.id(); case Parallel n->n.id(); case Fan n->n.id();
            case Loop n->n.id(); case Child n->n.id(); case Timer n->n.id(); case End n->"terminate";
        };
    }
    static Placement normalExit(Node node,Placement p) {
        return switch(node) {
            case Choice ignored -> p.child("join","choice",0);
            case Parallel ignored -> p.child("join","parallel",0);
            case Fan ignored -> p.child("join","fan",0);
            case Loop ignored -> p.child("exit","loop",0);
            default -> p;
        };
    }
    static Capability capability(Node node) {
        return switch(node) {
            case Call ignored -> Capability.OPERATION; case End ignored -> Capability.TERMINAL;
            case Choice c -> c.assessment()==null?Capability.DECISION:Capability.VERDICT;
            case Parallel ignored -> Capability.PARALLEL; case Fan ignored -> Capability.FAN;
            case Loop ignored -> Capability.LOOP; case Child ignored -> Capability.CHILD;
            case Timer ignored -> Capability.TIMER;
        };
    }
}
