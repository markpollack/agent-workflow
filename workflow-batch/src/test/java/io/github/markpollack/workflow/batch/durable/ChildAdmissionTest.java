package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import static io.github.markpollack.workflow.batch.durable.ChildFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildAdmissionTest {
    @TempDir Path directory;
    private static Placement placement(String workflow,String node,int ordinal) {
        return new Placement(List.of(new Segment("workflow",workflow,0),new Segment("node",node,ordinal)));
    }
    public static class ChildReturn implements DurableOperation<Second,First> {
        public First execute(Second input,DeliveryContext context,Map<String,String> config) { return new First("changed-first"); }
    }

    @Test void parentRetainsExactAssembledChildInputWithIndependentMappingOracle() throws Exception {
        var deployment=new ApplicationDeployment("child-input","build-1",Map.of("evidence",directory.toString()),
                List.of(FirstOperation.class,SecondOperation.class,ChildReturn.class,ChangedFifth.class));
        var childDefinition=new Definition<>("child",SecondInput.class,First.class,List.of(
                new Call("second",Op.named("second",SecondInput.class,Second.class)),
                new Call("changed",Op.named("changed",Second.class,First.class)),new End(Terminal.SUCCEEDED,"")),DURATION);
        var child=ValidatedWorkflow.compile(childDefinition,Map.of(
                placement("child","second",0),deployment.selection(SecondOperation.class,SecondInput.class,Second.class),
                placement("child","changed",1),deployment.selection(ChildReturn.class,Second.class,First.class)));
        var definition=new Definition<>("parent",Request.class,Reply.class,List.of(
                new Call("first",Op.named("first",Request.class,First.class)),new Child("inspect",childDefinition),
                new Call("last",Op.named("last",ChangedFifthInput.class,Reply.class)),new End(Terminal.SUCCEEDED,"")),DURATION);
        var parent=ValidatedWorkflow.compileWithChildren(definition,Map.of(
                placement("parent","first",0),deployment.selection(FirstOperation.class,Request.class,First.class),
                placement("parent","last",2),deployment.selection(ChangedFifth.class,ChangedFifthInput.class,Reply.class)),
                Map.of(placement("parent","inspect",1),child),DeadlinePolicy.DEFAULT);
        // This oracle names authored destinations and exact producers, independently of traversal order.
        var input=parent.values().get(parent.invocations().get(1).input());
        assertThat(input.declaration()).isEqualTo(SecondInput.class);
        assertThat(input.components()).containsExactly(parent.invocations().getFirst().output(),
                new ValueId(new Placement(List.of(new Segment("workflow","parent",0))),"root","root"));
        var finalInput=parent.values().get(parent.invocations().getLast().input());
        assertThat(finalInput.components()).containsExactly(parent.invocations().get(1).output(),input.identity());
        Path file=directory.resolve("runs");String id;RunSnapshot.Value saved;
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            id=runtime.start(parent,"key",new Request("original")).runId();var lease=runtime.claim(id,parent,"first");
            runtime.advance(lease,parent);var waiting=runtime.advance(lease,parent);
            saved=waiting.values().stream().filter(v->v.declaredType().equals(SecondInput.class.getTypeName())).findFirst().orElseThrow();
        }
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            runtime.resume(id,parent);
            assertThat(runtime.result(id,parent)).isEqualTo(new Reply("first:original/original/changed-first"));
            assertThat(runtime.inspect(id).values()).contains(saved);
        }
    }

    @Test void exactSelectionsOwnedDefinitionsAndUnsupportedCombinationsRefuseBeforeAdmission() {
        var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        var definition=built.parent().definition();var where=placement("parent","child-0",0);
        assertThatThrownBy(()->ValidatedWorkflow.compile(definition,Map.of())).hasMessageContaining("unsupported production capability");
        assertThatThrownBy(()->ValidatedWorkflow.compileWithChildren(definition,Map.of(),Map.of(),DeadlinePolicy.DEFAULT)).hasMessageContaining("missing validated child");
        assertThatThrownBy(()->ValidatedWorkflow.compileWithChildren(definition,Map.of(),Map.of(where,built.child(),placement("other","extra",0),built.child()),DeadlinePolicy.DEFAULT)).hasMessageContaining("extraneous");
        var different=echo(deployment,Echo.class,Duration.ofSeconds(1));
        assertThatThrownBy(()->ValidatedWorkflow.compileWithChildren(definition,Map.of(),Map.of(where,different),DeadlinePolicy.DEFAULT)).hasMessageContaining("disagreement");
        var nested=new Definition<>("outer",Request.class,Request.class,List.of(new Child("nested",definition),new End(Terminal.SUCCEEDED,"")),DURATION);
        assertThatThrownBy(()->ValidatedWorkflow.compileWithChildren(nested,Map.of(),Map.of(placement("outer","nested",0),built.parent()),DeadlinePolicy.DEFAULT)).hasMessageContaining("nested child");
        var timer=new Definition<>("timer",Request.class,Request.class,List.of(new WorkflowModel.Timer("wait",Duration.ZERO),new End(Terminal.SUCCEEDED,"")),DURATION);
        assertThatThrownBy(()->ValidatedWorkflow.compileWithChildren(timer,Map.of(),Map.of(),DeadlinePolicy.DEFAULT)).hasMessageContaining("unsupported production capability");
        Map<Placement,ValidatedWorkflow> mutable=new HashMap<>();mutable.put(where,built.child());
        var owned=ValidatedWorkflow.compileWithChildren(definition,Map.of(),mutable,DeadlinePolicy.DEFAULT);mutable.clear();
        assertThat(owned.children()).hasSize(1);assertThatThrownBy(()->owned.children().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void changedChildSelectionAndRegistrationRefuseWithoutDispatchOrCharge() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        var changed=repeated(deployment,1,NullOutput.class,Terminal.SUCCEEDED,DURATION,DURATION);
        assertThat(changed.parent().authoredIdentity()).isEqualTo(built.parent().authoredIdentity());
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();String before=StoreTestSupport.state(file,id);
            assertThatThrownBy(()->runtime.resume(id,changed.parent())).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("selection");
            assertThat(StoreTestSupport.state(file,id)).isEqualTo(before);
            var missing=new ApplicationDeployment("kernel-fixtures","fixture-v1",Map.of(),List.of(NullOutput.class));
            try(var other=DurableWorkflows.open(file,missing)) {
                assertThatThrownBy(()->other.resume(id,built.parent())).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("not registered");
                assertThat(StoreTestSupport.state(file,id)).isEqualTo(before);
            }
        }
    }

    @Test void parentCannotConsumePrivateChildValues() {
        var deployment=deployment(Map.of());var child=KernelFixtures.o01(deployment);
        var authoredChild=new Definition<>(child.definition().name(),child.definition().input(),child.definition().output(),child.definition().nodes(),null);
        var definition=new Definition<>("private",Request.class,Reply.class,List.of(new Child("child",authoredChild),
                new Call("leak",Op.named("leak",SecondInput.class,Reply.class)),new End(Terminal.SUCCEEDED,"")),DURATION);
        assertThatThrownBy(()->ValidatedWorkflow.compileWithChildren(definition,Map.of(),Map.of(placement("private","child",0),child),DeadlinePolicy.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unbound");
    }
}
