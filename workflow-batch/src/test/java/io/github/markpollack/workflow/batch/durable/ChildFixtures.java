package io.github.markpollack.workflow.batch.durable;

import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;

final class ChildFixtures {
    static final Duration DURATION=Duration.ofMinutes(5);
    record Built(ValidatedWorkflow parent,ValidatedWorkflow child) {}

    static Built repeated(ApplicationDeployment deployment,int count,Class<? extends DurableOperation<?,?>> operation,
            Terminal terminal,Duration parentDuration,Duration childDuration) {
        var child=KernelFixtures.single(deployment,operation,Request.class,Request.class,childDuration,terminal);
        List<Node> nodes=new ArrayList<>();
        for(int i=0;i<count;i++) nodes.add(new Child("child-"+i,child.definition()));
        if(terminal==Terminal.SUCCEEDED) nodes.add(new End(Terminal.SUCCEEDED,""));
        var definition=new Definition<>("parent",Request.class,Request.class,nodes,parentDuration);
        var compilation=StructuredWorkflowCompiler.compile(definition);
        Map<Placement,ValidatedWorkflow> children=new LinkedHashMap<>();
        compilation.bindings().forEach(call->children.put(call.placement(),child));
        return new Built(ValidatedWorkflow.compileWithChildren(definition,Map.of(),children,DeadlinePolicy.DEFAULT),child);
    }

    static Built o01(ApplicationDeployment deployment) {
        var child=KernelFixtures.o01(deployment,true);
        // Preserve the child's authored default, rather than substitute its resolved duration.
        var childDefinition=new Definition<>(child.definition().name(),child.definition().input(),child.definition().output(),child.definition().nodes(),null);
        var definition=new Definition<>("parent-o01",Request.class,Reply.class,
                List.of(new Child("calculate",childDefinition),new End(Terminal.SUCCEEDED,"")),DURATION);
        Placement call=new Placement(List.of(new Segment("workflow","parent-o01",0),new Segment("node","calculate",0)));
        return new Built(ValidatedWorkflow.compileWithChildren(definition,Map.of(),Map.of(call,child),DeadlinePolicy.DEFAULT),child);
    }

    static RunSnapshot accept(DurableWorkflows runtime,String id,ValidatedWorkflow parent,String worker) {
        return runtime.advance(runtime.claim(id,parent,worker),parent);
    }
    static String childId(RunSnapshot parent) { return parent.invocations().get(parent.nextOperation()).childRunId(); }
}
