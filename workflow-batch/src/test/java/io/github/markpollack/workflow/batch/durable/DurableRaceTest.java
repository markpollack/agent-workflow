package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

class DurableRaceTest {
    @TempDir Path directory;
    @Test void concurrentIdenticalAdmissionsReturnOneDurableRun() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        try(var runtime=DurableWorkflows.open(file,deployment);var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start=new CountDownLatch(1);List<Future<RunSnapshot>> calls=new ArrayList<>();
            for(int i=0;i<4;i++)calls.add(threads.submit(()->{start.await();return runtime.start(workflow,"shared",new Request("same"));}));
            start.countDown();Set<String> ids=new HashSet<>();for(var call:calls)ids.add(call.get(20,TimeUnit.SECONDS).runId());
            assertThat(ids).hasSize(1);assertThat(runtime.discover()).hasSize(1);var snapshot=runtime.discover().getFirst();
            assertThat(snapshot.events()).hasSize(1);assertThat(snapshot.values()).hasSize(1);evidence(file,snapshot.runId(),"concurrent-admission");
        }
    }
    @Test void duplicateAdvanceCannotExhaustFinalLiveDelivery() throws Exception {
        Path file=directory.resolve("runs"),signals=directory.resolve("signals");Files.createDirectories(signals);
        var deployment=deployment(Map.of("evidence",signals.toString()));var workflow=echo(deployment,Blocking.class,null);
        try(var runtime=DurableWorkflows.open(file,deployment,new ExecutionPolicy(1));var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            controlledClock(file,500_000);var run=runtime.start(workflow,"last-delivery",new Request("x"));
            String lease=run.runId();var active=threads.submit(()->runtime.advance(lease,workflow));await(signals.resolve("entered-1"));
            assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("already executing");
            assertThat(runtime.inspect(run.runId()).status()).isEqualTo(RunSnapshot.Status.ACTIVE);
            Files.writeString(signals.resolve("release-1"),"release");assertThat(active.get(20,TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.inspect(run.runId()).invocations().getFirst().attempts()).hasSize(1);evidence(file,run.runId(),"final-live-delivery");
        }
    }
    @Test void deadlineEqualityRevokesLateCompletionAndDiscoveryMaterializesExpiry() throws Exception {
        Path file=directory.resolve("runs"),signals=directory.resolve("signals");Files.createDirectories(signals);
        var deployment=deployment(Map.of("evidence",signals.toString()));var workflow=echo(deployment,Blocking.class,Duration.ofMinutes(1));
        try(var runtime=DurableWorkflows.open(file,deployment);var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            controlledClock(file,2_000_000);var run=runtime.start(workflow,"expiry",new Request("x"));
            assertThat(run.deadlineOrigin()).isEqualTo("AUTHORED:local-v1");
            String lease=run.runId();var completion=threads.submit(()->runtime.advance(lease,workflow));await(signals.resolve("entered-1"));
            time(run.deadline().toEpochMilli());Files.writeString(signals.resolve("release-1"),"release");
            var expired=completion.get(20,TimeUnit.SECONDS);assertThat(expired.status()).isEqualTo(RunSnapshot.Status.FAILED);
            assertThat(expired.reason().code()).isEqualTo("DEADLINE_EXCEEDED");assertThat(expired.nextOperation()).isZero();
            assertThat(expired.events()).noneMatch(e->e.kind().equals("RESULT_COMMITTED"));evidence(file,run.runId(),"deadline-equality-result");
            var second=runtime.start(workflow,"undispatched",new Request("x"));time(second.deadline().toEpochMilli());
            assertThat(runtime.discover()).isEmpty();assertThat(runtime.inspect(second.runId()).reason().code()).isEqualTo("DEADLINE_EXCEEDED");
            evidence(file,second.runId(),"discovery-expiry");
        }
    }
    @Test void cancellationWinsBeforeCompletionAndCompletionWinsBeforeCancellation() throws Exception {
        Path file=directory.resolve("runs"),signals=directory.resolve("signals");Files.createDirectories(signals);
        var deployment=deployment(Map.of("evidence",signals.toString()));var workflow=echo(deployment,Blocking.class,null);
        try(var runtime=DurableWorkflows.open(file,deployment);var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            controlledClock(file,3_000_000);var run=runtime.start(workflow,"cancel",new Request("x"));
            String lease=run.runId();var result=threads.submit(()->runtime.advance(lease,workflow));await(signals.resolve("entered-1"));
            var cancelled=runtime.cancel(run.runId(),"owner","stop");Files.writeString(signals.resolve("release-1"),"release");result.get(20,TimeUnit.SECONDS);
            assertThat(runtime.inspect(run.runId())).isEqualTo(cancelled);assertThat(runtime.resume(run.runId(),workflow)).isEqualTo(cancelled);
            assertThat(cancelled.invocations().getFirst().status()).isEqualTo("CANCELLED");evidence(file,run.runId(),"cancellation-first");
            var fast=echo(deployment,Echo.class,null);var before=runtime.start(fast,"complete",new Request("x"));
            var complete=runtime.resume(before.runId(),fast);time(before.deadline().toEpochMilli());
            assertThat(runtime.cancel(before.runId(),"owner","late")).isEqualTo(complete);evidence(file,before.runId(),"completion-first");
        }
    }
    @Test void unresolvedAttemptsAreBoundedAcrossReopenWithoutMovingDeadline() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        var policy=new ExecutionPolicy(2);
        BoundaryHooks crash=(point,run)->{if(point.equals("AFTER_DISPATCH_COMMIT"))throw new SimulatedCrash();};
        RunSnapshot admitted,initial;
        try(var runtime=new DurableWorkflows(file,deployment,policy,crash)) {
            admitted=runtime.start(workflow,"attempts",new Request("same"));
            assertThatThrownBy(()->runtime.advance(admitted.runId(),workflow)).isInstanceOf(SimulatedCrash.class);
            initial=runtime.inspect(admitted.runId());
        }
        try(var runtime=new DurableWorkflows(file,deployment,policy,crash)) {
            assertThatThrownBy(()->runtime.advance(admitted.runId(),workflow)).isInstanceOf(SimulatedCrash.class);
        }
        try(var runtime=DurableWorkflows.open(file,deployment,policy)) {
            var exhausted=runtime.resume(admitted.runId(),workflow);
            assertThat(exhausted.reason().code()).isEqualTo("ATTEMPTS_EXHAUSTED");
            assertThat(exhausted.invocations().getFirst().attempts()).hasSize(2);
            assertThat(exhausted.values()).isEqualTo(initial.values());
            assertThat(exhausted.deadline()).isEqualTo(admitted.deadline());
            assertThat(runtime.resume(admitted.runId(),workflow)).isEqualTo(exhausted);
            evidence(file,admitted.runId(),"attempt-exhaustion");
        }
    }
    @Test void transactionFaultsRollBackValuesContinuationAndEventsTogether() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        AtomicReference<String> point=new AtomicReference<>("BEFORE_ADMISSION_COMMIT");
        BoundaryHooks hooks=(at,run)->{if(at.equals(point.get()))throw new SimulatedCrash();};
        try(var runtime=new DurableWorkflows(file,deployment,ExecutionPolicy.DEFAULT,hooks)) {
            controlledClock(file,5_000_000);
            assertThatThrownBy(()->runtime.start(workflow,"rollback",new Request("x"))).isInstanceOf(SimulatedCrash.class);
            assertThat(runtime.discover()).isEmpty();point.set("");var run=runtime.start(workflow,"rollback",new Request("x"));
            String lease=run.runId();point.set("BEFORE_DISPATCH_COMMIT");
            assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(SimulatedCrash.class);
            assertThat(runtime.inspect(run.runId()).invocations()).isEmpty();
            point.set("BEFORE_RESULT_COMMIT");assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(SimulatedCrash.class);
            var rolled=runtime.inspect(run.runId());assertThat(rolled.nextOperation()).isZero();assertThat(rolled.values()).hasSize(1);
            assertThat(rolled.events()).noneMatch(e->e.kind().equals("RESULT_COMMITTED"));assertThat(rolled.invocations().getFirst().attempts()).hasSize(1);
            evidence(file,run.runId(),"result-transaction-rollback");
            time(5_030_000);point.set("");assertThat(runtime.resume(run.runId(),workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
        }
    }
    @Test void cancellationAndDeadlineAtCommittedDispatchPreventApplicationEntry() throws Exception {
        for(boolean cancel:List.of(false,true)) {
            Path file=directory.resolve("entry-"+cancel),signals=directory.resolve("effects-"+cancel);
            var deployment=deployment(Map.of("evidence",signals.toString()));var workflow=echo(deployment,Echo.class,null);
            AtomicReference<DurableWorkflows> handle=new AtomicReference<>();
            BoundaryHooks hook=(point,id)->{
                if(point.equals("AFTER_DISPATCH_COMMIT")) {
                    if(cancel) handle.get().cancel(id,"owner","before application entry");
                    else time(handle.get().inspect(id).deadline().toEpochMilli());
                }
            };
            try(var runtime=new DurableWorkflows(file,deployment,ExecutionPolicy.DEFAULT,hook)) {
                handle.set(runtime);controlledClock(file,9_000_000);
                var run=runtime.start(workflow,"entry",new Request("x"));var after=runtime.resume(run.runId(),workflow);
                assertThat(after.status()).isEqualTo(cancel?RunSnapshot.Status.CANCELLED:RunSnapshot.Status.FAILED);
                assertThat(after.reason().code()).isEqualTo(cancel?"CANCELLED":"DEADLINE_EXCEEDED");
                assertThat(after.invocations().getFirst().attempts()).hasSize(1);
                assertThat(after.events()).noneMatch(e->e.kind().equals("RESULT_COMMITTED"));
                assertThat(Files.exists(signals.resolve("echo.calls"))).isFalse();
            }
        }
    }
    static final class SimulatedCrash extends Error {}
}
