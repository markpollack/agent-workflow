package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import static io.github.markpollack.workflow.batch.durable.ChildFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalChildrenTest {
    @TempDir Path directory;

    @Test void atomicChildDiscoveryReattachmentExactValuesAndSingleSettlement() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of("evidence",directory.toString()));
        var built=ChildFixtures.o01(deployment);String id,childId;RunSnapshot childAdmission;
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            id=runtime.start(built.parent(),"parent",new Request("original")).runId();
            var waiting=accept(runtime,id,built.parent(),"acceptor");childId=childId(waiting);
            assertThat(waiting.owner()).isEmpty();assertThat(waiting.leaseUntil()).isNull();
            assertThat(waiting.lineage().acceptedDescendants()).isEqualTo(1);
            assertThat(waiting.invocations().getFirst().deliveries()).isEmpty();
            childAdmission=runtime.inspect(childId);
            assertThat(childAdmission.lineage().parentRunId()).isEqualTo(id);
            assertThat(childAdmission.lineage().rootRunId()).isEqualTo(id);
            assertThat(childAdmission.deadline()).isEqualTo(waiting.deadline());
            assertThat(childAdmission.deadlineOrigin()).startsWith("INHERITED:");
            var parentInput=waiting.values().getFirst();var childInput=childAdmission.values().getFirst();
            assertThat(childInput.payload()).isEqualTo(parentInput.payload());
            assertThat(childInput.sourceRunId()).isEqualTo(id);assertThat(childInput.sourceValueId()).isEqualTo(parentInput.valueId());
            for(int i=0;i<3;i++) {
                var again=accept(runtime,id,built.parent(),"reattach-"+i);
                assertThat(childId(again)).isEqualTo(childId);assertThat(again.lineage().acceptedDescendants()).isEqualTo(1);
                assertThat(runtime.inspect(childId)).isEqualTo(childAdmission);
            }
        }
        try(var management=DurableWorkflows.open(file)) {
            assertThat(management.discover()).extracting(RunSnapshot::runId).containsExactlyInAnyOrder(id,childId);
        }
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            assertThat(runtime.resume(id,built.parent()).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.result(id,built.parent())).isEqualTo(new Reply("first:original/original/changed-first"));
            var parent=runtime.inspect(id);var child=runtime.inspect(childId);
            assertThat(parent.events()).filteredOn(e->e.kind().equals("CHILD_SETTLED")).hasSize(1);
            assertThat(parent.lineage().acceptedDescendants()).isEqualTo(1);
            assertThat(child.deadline()).isEqualTo(childAdmission.deadline());
            var output=parent.values().stream().filter(v->v.sourceRunId().equals(childId)).findFirst().orElseThrow();
            assertThat(child.values()).anySatisfy(v->{assertThat(v.valueId()).isEqualTo(output.sourceValueId());assertThat(v.payload()).isEqualTo(output.payload());});
            String saved=StoreTestSupport.state(file,id);
            runtime.resume(id,built.parent());assertThat(StoreTestSupport.state(file,id)).isEqualTo(saved);
        }
    }

    @ParameterizedTest @ValueSource(strings={"BEFORE_CHILD_RESERVATION","AFTER_CHILD_CREATE_BEFORE_COMMIT","BEFORE_CHILD_ACCEPT_COMMIT"})
    void rollbackCannotLeaveReservationChildChargeOrInput(String boundary) throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        AtomicBoolean fail=new AtomicBoolean(true);
        try(var runtime=new DurableWorkflows(file,deployment,ExecutionPolicy.DEFAULT,(point,id)->{
            if(point.equals(boundary)&&fail.getAndSet(false)) throw new IllegalStateException("cut");
        })) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();
            var lease=runtime.claim(id,built.parent(),"worker");String before=StoreTestSupport.state(file,id);
            assertThatThrownBy(()->runtime.advance(lease,built.parent())).isInstanceOf(IllegalStateException.class);
            assertThat(StoreTestSupport.state(file,id)).isEqualTo(before);
            assertThat(runtime.discover()).hasSize(1);
            var accepted=runtime.advance(lease,built.parent());
            assertThat(accepted.lineage().acceptedDescendants()).isEqualTo(1);assertThat(runtime.discover()).hasSize(2);
        }
    }

    @Test void concurrentDuplicateAdvanceChargesOnceAndFencesOldParentLease() throws Exception {
        var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();var lease=runtime.claim(id,built.parent(),"one");
            CountDownLatch go=new CountDownLatch(1);
            List<Future<String>> tasks=new ArrayList<>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()->{go.await();try{return runtime.advance(lease,built.parent()).status().name();}catch(WorkflowRefusal ex){return ex.code();}}));
            go.countDown();assertThat(tasks.stream().map(f->{try{return f.get();}catch(Exception ex){throw new AssertionError(ex);}}).toList())
                    .containsExactlyInAnyOrder("ACTIVE","FENCED");
            assertThat(runtime.inspect(id).lineage().acceptedDescendants()).isEqualTo(1);assertThat(runtime.discover()).hasSize(2);
            assertThatThrownBy(()->runtime.renew(lease)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("authority");
        }
    }

    @Test void nonreturningChildCancellationMapsToParentFailureAndPreservesChildEvidence() {
        var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.CANCELLED,DURATION,DURATION);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();
            var result=runtime.resume(id,built.parent());assertThat(result.status()).isEqualTo(RunSnapshot.Status.FAILED);
            assertThat(result.reason().code()).isEqualTo("CHILD_CANCELLED");
            var call=result.invocations().getFirst();assertThat(call.status()).isEqualTo("FAILED");
            assertThat(call.childOutcome()).isEqualTo("CANCELLED");assertThat(call.settledAt()).isNotNull();
            assertThat(runtime.inspect(call.childRunId()).reason().code()).isEqualTo("AUTHORED_CANCELLED");
        }
    }

    @ParameterizedTest @ValueSource(strings={"throw","encode","authored"})
    void childFailuresSettleWithoutPublishingSuccessfulParentOutput(String mode) {
        var deployment=deployment(Map.of());
        var handler=mode.equals("throw")?Throws.class:mode.equals("encode")?NullOutput.class:Echo.class;
        var built=repeated(deployment,1,handler,mode.equals("authored")?Terminal.FAILED:Terminal.SUCCEEDED,DURATION,DURATION);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();var result=runtime.resume(id,built.parent());
            assertThat(result.status()).isEqualTo(RunSnapshot.Status.FAILED);assertThat(result.reason().code()).isEqualTo("CHILD_FAILED");
            var call=result.invocations().getFirst();var child=runtime.inspect(call.childRunId());
            assertThat(child.reason().code()).isEqualTo(mode.equals("throw")?"HANDLER_FAILED":mode.equals("encode")?"OUTPUT_ENCODING_FAILED":"AUTHORED_FAILED");
            assertThat(call.childReason()).isEqualTo(child.reason().code());
            assertThat(result.values()).noneMatch(v->v.valueId().equals(call.outputValue()));
            assertThat(result.events()).filteredOn(e->e.kind().equals("CHILD_SETTLED")).hasSize(1);
        }
    }

    @Test void descendantAndDepthBoundsRefuseBeforeAnyExcessChildIsCreated() {
        var deployment=deployment(Map.of());var built=repeated(deployment,2,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        for(int depth:List.of(0,1)) try(var runtime=DurableWorkflows.open(directory.resolve("bound-"+depth),deployment,new ExecutionPolicy(Duration.ofSeconds(5),3,depth,1))) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();
            var result=runtime.resume(id,built.parent());assertThat(result.reason().code()).isEqualTo("CHILD_LIMIT_EXCEEDED");
            assertThat(result.lineage().acceptedDescendants()).isEqualTo(depth);
            assertThat(result.invocations()).filteredOn(i->!i.childRunId().isEmpty()).hasSize(depth);
        }
    }

    @Test void tighterChildDeadlineFailsTheCallWithoutCancellingParent() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,Duration.ofSeconds(1));
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            long now=System.currentTimeMillis()+1000;StoreTestSupport.controlledClock(file,now);
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();var waiting=accept(runtime,id,built.parent(),"parent");
            var child=runtime.inspect(childId(waiting));assertThat(child.deadline()).isBefore(waiting.deadline());
            StoreTestSupport.time(child.deadline().toEpochMilli());
            assertThat(runtime.resume(id,built.parent()).reason().code()).isEqualTo("CHILD_FAILED");
            assertThat(runtime.inspect(child.runId()).reason().code()).isEqualTo("DEADLINE_EXCEEDED");
        }
    }

    @Test void parentExpiryObservedThroughChildSealsBothAndPreventsContinuation() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,Duration.ofSeconds(1),DURATION);
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            long now=System.currentTimeMillis()+1000;StoreTestSupport.controlledClock(file,now);
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();var waiting=accept(runtime,id,built.parent(),"parent");
            String childId=childId(waiting);var childLease=runtime.claim(childId,built.child(),"child");
            StoreTestSupport.time(waiting.deadline().toEpochMilli());
            assertThat(runtime.advance(childLease,built.child()).reason().code()).isEqualTo("DEADLINE_EXCEEDED");
            var parent=runtime.inspect(id);assertThat(parent.reason().code()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(parent.invocations().getFirst().childOutcome()).isEqualTo("FAILED");
            assertThat(parent.events()).filteredOn(e->e.kind().equals("CHILD_SETTLED")).hasSize(1);
            assertThat(runtime.discover()).isEmpty();
        }
    }

    @Test void childOwnedElsewhereReleasesParentWorkerAndCancellationFencesLateOutput() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of("evidence",directory.toString()));var built=repeated(deployment,1,Blocking.class,Terminal.SUCCEEDED,DURATION,DURATION);
        try(var runtime=DurableWorkflows.open(file,deployment);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();var waiting=accept(runtime,id,built.parent(),"parent");String childId=childId(waiting);
            var childLease=runtime.claim(childId,built.child(),"child");var pending=pool.submit(()->runtime.advance(childLease,built.child()));
            StoreTestSupport.await(directory.resolve("entered-"+childLease.generation()));
            try {
                assertThat(pool.submit(()->runtime.resume(id,built.parent())).get(2,TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.ACTIVE);
                runtime.cancel(id,"owner","stop");
                var child=runtime.inspect(childId);assertThat(child.status()).isEqualTo(RunSnapshot.Status.CANCELLED);
                String cancelled=StoreTestSupport.state(file,childId);
                java.nio.file.Files.writeString(directory.resolve("release-"+childLease.generation()),"go");
                assertThat(pending.get(5,TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.CANCELLED);
                assertThat(StoreTestSupport.state(file,childId)).isEqualTo(cancelled);
                assertThatThrownBy(()->runtime.renew(childLease)).isInstanceOf(WorkflowRefusal.class);
                assertThat(runtime.inspect(id).invocations().getFirst().childOutcome()).isEqualTo("CANCELLED");
            } finally {java.nio.file.Files.writeString(directory.resolve("release-"+childLease.generation()),"go");}
        }
    }

    @Test void completedChildCannotReviveCancelledParentAndItsEvidenceIsPreserved() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();String childId=childId(accept(runtime,id,built.parent(),"parent"));
            runtime.resume(childId,built.child());String completed=StoreTestSupport.state(file,childId);
            var result=runtime.cancel(id,"owner","stop");assertThat(result.status()).isEqualTo(RunSnapshot.Status.CANCELLED);
            assertThat(result.invocations().getFirst().childOutcome()).isEqualTo("SUCCEEDED");
            assertThat(result.events()).filteredOn(e->e.kind().equals("CHILD_SETTLED"))
                    .singleElement().satisfies(e->assertThat(e.detail()).endsWith(":SUCCEEDED"));
            assertThat(runtime.resume(id,built.parent()).status()).isEqualTo(RunSnapshot.Status.CANCELLED);
            assertThat(StoreTestSupport.state(file,childId)).isEqualTo(completed);
            assertThat(result.values()).hasSize(1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"parentInvocation","sourceValue","payload","output"})
    void missingOrChangedCommittedChildFactsNeverRecompute(String mutation) throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of("evidence",directory.toString()));var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();String childId=childId(accept(runtime,id,built.parent(),"parent"));
            runtime.resume(childId,built.child());
            StoreTestSupport.mutate(file,childId,state->{
                if(mutation.equals("parentInvocation")) ((ObjectNode)state).put(mutation,"foreign");
                else if(mutation.equals("output")) ((ObjectNode)state.path("values")).remove(state.path("output").asText());
                else {var root=(ObjectNode)state.path("values").elements().next();root.put(mutation,mutation.equals("payload")?"e30=":"foreign");}
            });
            var lease=runtime.claim(id,built.parent(),"settle");String before=StoreTestSupport.state(file,id);
            assertThatThrownBy(()->runtime.advance(lease,built.parent())).isInstanceOf(WorkflowRefusal.class);
            assertThat(StoreTestSupport.state(file,id)).isEqualTo(before);
            assertThat(java.nio.file.Files.readAllLines(directory.resolve("echo.calls"))).hasSize(1);
        }
    }

    @Test void corruptedAttachmentRefusesBeforeConvenienceResumeCanDriveTheChild() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of("evidence",directory.toString()));
        var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            String first=runtime.start(built.parent(),"first",new Request("first")).runId();
            String firstChild=childId(accept(runtime,first,built.parent(),"first"));
            String second=runtime.start(built.parent(),"second",new Request("second")).runId();
            String secondChild=childId(accept(runtime,second,built.parent(),"second"));
            StoreTestSupport.mutate(file,first,state->((ObjectNode)state.path("invocations").get(0)).put("childId",secondChild));
            String before=StoreTestSupport.state(file,secondChild);
            assertThatThrownBy(()->runtime.resume(first,built.parent())).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("child identity changed");
            assertThat(StoreTestSupport.state(file,secondChild)).isEqualTo(before);
            assertThat(runtime.inspect(firstChild).invocations()).isEmpty();
            assertThat(java.nio.file.Files.exists(directory.resolve("echo.calls"))).isFalse();
        }
    }

    @Test void replacedParentLeaseCannotSettleAndSettlementRollbackCanBeRetriedExactlyOnce() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());
        var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        AtomicBoolean fail=new AtomicBoolean(true);
        try(var runtime=new DurableWorkflows(file,deployment,new ExecutionPolicy(Duration.ofSeconds(1),3),(point,id)->{
            if(point.equals("BEFORE_CHILD_SETTLEMENT_COMMIT")&&fail.getAndSet(false)) throw new IllegalStateException("settlement cut");
        })) {
            long now=System.currentTimeMillis()+1000;StoreTestSupport.controlledClock(file,now);
            String id=runtime.start(built.parent(),"key",new Request("root")).runId();
            String childId=childId(accept(runtime,id,built.parent(),"acceptor"));runtime.resume(childId,built.child());
            String childEvidence=StoreTestSupport.state(file,childId);
            var stale=runtime.claim(id,built.parent(),"stale");StoreTestSupport.time(now+1000);
            var replacement=runtime.claim(id,built.parent(),"replacement");
            String before=StoreTestSupport.state(file,id);
            assertThatThrownBy(()->runtime.advance(stale,built.parent())).isInstanceOf(WorkflowRefusal.class);
            assertThat(StoreTestSupport.state(file,id)).isEqualTo(before);
            assertThatThrownBy(()->runtime.advance(replacement,built.parent())).isInstanceOf(IllegalStateException.class);
            assertThat(StoreTestSupport.state(file,id)).isEqualTo(before);
            assertThat(StoreTestSupport.state(file,childId)).isEqualTo(childEvidence);
            var result=runtime.advance(replacement,built.parent());assertThat(result.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.advance(replacement,built.parent()).events()).isEqualTo(result.events());
            assertThat(result.events()).filteredOn(e->e.kind().equals("CHILD_SETTLED")).hasSize(1);
        }
    }
}
