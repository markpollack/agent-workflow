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
    @Test void completionDrainsRenewalWithoutInterruptingDatabaseThread() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        CountDownLatch terminal=new CountDownLatch(1),renewing=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean interrupted=new AtomicBoolean();
        BoundaryHooks hooks=(point,run)->{
            try {
                if(point.equals("AFTER_RESULT_COMMIT")) {terminal.countDown();if(!renewing.await(10,TimeUnit.SECONDS))throw new AssertionError("renewal timeout");}
                if(point.equals("AFTER_RENEWAL_READ")&&terminal.getCount()==0) {
                    renewing.countDown();
                    try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");}
                    catch(InterruptedException ex){interrupted.set(true);throw new AssertionError("database renewal interrupted",ex);}
                }
            } catch(InterruptedException ex){throw new AssertionError(ex);}
        };
        try(var runtime=new DurableWorkflows(file,deployment,new ExecutionPolicy(Duration.ofSeconds(1),3),hooks);var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var run=runtime.start(workflow,"teardown",new Request("x"));var result=threads.submit(()->runtime.resume(run.runId(),workflow));
            assertThat(renewing.await(10,TimeUnit.SECONDS)).isTrue();Thread.sleep(100);assertThat(interrupted).isFalse();assertThat(result.isDone()).isFalse();
            release.countDown();assertThat(result.get(20,TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.result(run.runId(),workflow)).isEqualTo(new Request("x"));evidence(file,run.runId(),"renewal-drain");
        } finally {release.countDown();}
    }
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
    @Test void concurrentFirstOpensShareOneInitializedStore() throws Exception {
        Path file=directory.resolve("runs");CountDownLatch start=new CountDownLatch(1);
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> opens=new ArrayList<>();
            for(int i=0;i<8;i++) opens.add(threads.submit(()->{start.await();try(var runtime=DurableWorkflows.open(file)){return runtime.discover().size();}}));
            start.countDown();for(var future:opens)assertThat(future.get(20,TimeUnit.SECONDS)).isZero();
        }
    }
    @Test void duplicateAdvanceCannotExhaustFinalLiveDelivery() throws Exception {
        Path file=directory.resolve("runs"),signals=directory.resolve("signals");Files.createDirectories(signals);
        var deployment=deployment(Map.of("evidence",signals.toString()));var workflow=echo(deployment,Blocking.class,null);
        try(var runtime=DurableWorkflows.open(file,deployment,new ExecutionPolicy(Duration.ofSeconds(30),1));var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            controlledClock(file,500_000);var run=runtime.start(workflow,"last-delivery",new Request("x"));
            var lease=runtime.claim(run.runId(),workflow,"one");var active=threads.submit(()->runtime.advance(lease,workflow));await(signals.resolve("entered-1"));
            assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("already dispatched");
            assertThat(runtime.inspect(run.runId()).status()).isEqualTo(RunSnapshot.Status.ACTIVE);
            Files.writeString(signals.resolve("release-1"),"release");assertThat(active.get(20,TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.inspect(run.runId()).invocations().getFirst().deliveries()).hasSize(1);evidence(file,run.runId(),"final-live-delivery");
        }
    }
    @Test void competingClaimantsAndReplacementFenceLateResultRenewalAndContinuation() throws Exception {
        Path file=directory.resolve("runs"),signals=directory.resolve("signals");Files.createDirectories(signals);
        var deployment=deployment(Map.of("evidence",signals.toString()));var workflow=echo(deployment,Blocking.class,null);
        var policy=new ExecutionPolicy(Duration.ofSeconds(30),3);
        try(var one=DurableWorkflows.open(file,deployment,policy);var two=DurableWorkflows.open(file,deployment,policy);var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            controlledClock(file,1_000_000);var run=one.start(workflow,"race",new Request("original"));
            CountDownLatch start=new CountDownLatch(1);
            Callable<DurableWorkflows.Lease> contender=()->{start.await();try{return one.claim(run.runId(),workflow,Thread.currentThread().toString());}catch(WorkflowRefusal ex){return null;}};
            var a=threads.submit(contender);var b=threads.submit(contender);start.countDown();
            var leases=Arrays.asList(a.get(),b.get());assertThat(leases.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            var old=leases.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            var late=threads.submit(()->one.advance(old,workflow));await(signals.resolve("entered-1"));
            String inputId=one.inspect(run.runId()).invocations().getFirst().inputValue();
            var input=one.inspect(run.runId()).values().stream().filter(v->v.valueId().equals(inputId)).findFirst().orElseThrow();
            time(1_030_000);var replacement=two.claim(run.runId(),workflow,"replacement");assertThat(replacement.generation()).isEqualTo(2);
            assertThatThrownBy(()->one.renew(old)).isInstanceOf(WorkflowRefusal.class);
            assertThatThrownBy(()->one.advance(old,workflow)).isInstanceOf(WorkflowRefusal.class);
            Files.writeString(signals.resolve("release-2"),"release");var accepted=two.advance(replacement,workflow);
            Files.writeString(signals.resolve("release-1"),"release");late.get(20,TimeUnit.SECONDS);
            var finalState=one.inspect(run.runId());assertThat(finalState.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(one.result(run.runId(),workflow)).isEqualTo(new Request("generation:2"));
            assertThat(finalState.invocations().getFirst().deliveries()).hasSize(2);
            assertThat(finalState.values()).contains(input);
            assertThat(finalState.events().stream().filter(e->e.kind().equals("RESULT_COMMITTED")).count()).isEqualTo(1);
            assertThat(finalState).isEqualTo(accepted);evidence(file,run.runId(),"stale-worker-replaced");
        }
    }
    @Test void deadlineEqualityRevokesLateCompletionAndDiscoveryMaterializesExpiry() throws Exception {
        Path file=directory.resolve("runs"),signals=directory.resolve("signals");Files.createDirectories(signals);
        var deployment=deployment(Map.of("evidence",signals.toString()));var workflow=echo(deployment,Blocking.class,Duration.ofMinutes(1));
        try(var runtime=DurableWorkflows.open(file,deployment);var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            controlledClock(file,2_000_000);var run=runtime.start(workflow,"expiry",new Request("x"));
            assertThat(run.deadlineOrigin()).isEqualTo("AUTHORED:local-v1");
            var lease=runtime.claim(run.runId(),workflow,"worker");var completion=threads.submit(()->runtime.advance(lease,workflow));await(signals.resolve("entered-1"));
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
            var lease=runtime.claim(run.runId(),workflow,"worker");var result=threads.submit(()->runtime.advance(lease,workflow));await(signals.resolve("entered-1"));
            var cancelled=runtime.cancel(run.runId(),"owner","stop");Files.writeString(signals.resolve("release-1"),"release");result.get(20,TimeUnit.SECONDS);
            assertThat(runtime.inspect(run.runId())).isEqualTo(cancelled);assertThat(runtime.resume(run.runId(),workflow)).isEqualTo(cancelled);
            assertThat(cancelled.invocations().getFirst().status()).isEqualTo("CANCELLED");evidence(file,run.runId(),"cancellation-first");
            var fast=echo(deployment,Echo.class,null);var before=runtime.start(fast,"complete",new Request("x"));
            var complete=runtime.resume(before.runId(),fast);time(before.deadline().toEpochMilli());
            assertThat(runtime.cancel(before.runId(),"owner","late")).isEqualTo(complete);evidence(file,before.runId(),"completion-first");
        }
    }
    @Test void renewalsDoNotExtendDeadlineOrResetDeliveryAndExhaustionIsDurable() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        var policy=new ExecutionPolicy(Duration.ofSeconds(10),2);AtomicBoolean crash=new AtomicBoolean(true);
        BoundaryHooks hooks=(point,run)->{if(point.equals("AFTER_DISPATCH_COMMIT")&&crash.get())throw new SimulatedCrash();};
        try(var runtime=new DurableWorkflows(file,deployment,policy,hooks)) {
            controlledClock(file,4_000_000);var run=runtime.start(workflow,"delivery",new Request("same"));
            var one=runtime.claim(run.runId(),workflow,"one");assertThatThrownBy(()->runtime.advance(one,workflow)).isInstanceOf(SimulatedCrash.class);
            var initial=runtime.inspect(run.runId());
            time(4_005_000);var renewed=runtime.renew(one);assertThat(renewed.deadline()).isEqualTo(run.deadline());
            assertThat(renewed.invocations()).isEqualTo(initial.invocations());
            assertThatThrownBy(()->runtime.advance(one,workflow)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("already dispatched");
            time(4_015_000);var two=runtime.claim(run.runId(),workflow,"two");assertThatThrownBy(()->runtime.advance(two,workflow)).isInstanceOf(SimulatedCrash.class);
            time(4_025_000);var three=runtime.claim(run.runId(),workflow,"three");crash.set(false);
            var exhausted=runtime.advance(three,workflow);assertThat(exhausted.reason().code()).isEqualTo("DELIVERY_EXHAUSTED");
            assertThat(exhausted.invocations().getFirst().deliveries()).hasSize(2);assertThat(exhausted.values()).isEqualTo(initial.values());
            evidence(file,run.runId(),"redelivery-exhaustion");
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
            var lease=runtime.claim(run.runId(),workflow,"one");point.set("BEFORE_DISPATCH_COMMIT");
            assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(SimulatedCrash.class);
            assertThat(runtime.inspect(run.runId()).invocations()).isEmpty();
            point.set("BEFORE_RESULT_COMMIT");assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(SimulatedCrash.class);
            var rolled=runtime.inspect(run.runId());assertThat(rolled.nextOperation()).isZero();assertThat(rolled.values()).hasSize(1);
            assertThat(rolled.events()).noneMatch(e->e.kind().equals("RESULT_COMMITTED"));assertThat(rolled.invocations().getFirst().deliveries()).hasSize(1);
            evidence(file,run.runId(),"result-transaction-rollback");
            time(5_030_000);point.set("");assertThat(runtime.resume(run.runId(),workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
        }
    }
    static final class SimulatedCrash extends Error {}
}
