package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

class DurableLifecycleTest {
    @TempDir Path directory;
    @Test void automaticRenewalCoversSlowHandlerWithoutMovingDeadline() throws Exception {
        var deployment=deployment(Map.of());var workflow=echo(deployment,Slow.class,null);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment,new ExecutionPolicy(Duration.ofSeconds(1),3))) {
            var admitted=runtime.start(workflow,"slow",new Request("x"));
            var result=runtime.resume(admitted.runId(),workflow);
            assertThat(result.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);assertThat(result.deadline()).isEqualTo(admitted.deadline());
            assertThat(result.events()).anyMatch(e->e.kind().equals("RENEWED"));assertThat(result.invocations().getFirst().deliveries()).hasSize(1);
        }
    }
    @Test void terminalOnlyAndDefaultPolicyRemainFiniteAndExplicit() throws Exception {
        var deployment=deployment(Map.of());
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            for(Terminal terminal:Terminal.values()) {
                var source=new Definition<>("terminal-"+terminal,Request.class,Request.class,List.<Node>of(new End(terminal,"reason")),null);
                var workflow=ValidatedWorkflow.compile(source,Map.of());var start=runtime.start(workflow,terminal.name(),new Request("x"));
                assertThat(runtime.resume(start.runId(),workflow).status().name()).isEqualTo(terminal.name());
            }
            var capped=echo(deployment,Echo.class,Duration.ofHours(2));var start=runtime.start(capped,"capped",new Request("x"));
            assertThat(start.deadline()).isEqualTo(start.admittedAt().plus(Duration.ofHours(1)));assertThat(start.deadlineOrigin()).startsWith("POLICY_CAP");
            for(Duration invalid:List.of(Duration.ZERO,Duration.ofSeconds(-1),Duration.ofSeconds(Long.MAX_VALUE)))
                assertThatThrownBy(()->ValidatedWorkflow.compile(new Definition<>("invalid",Request.class,Request.class,List.<Node>of(new End(Terminal.SUCCEEDED,"")),invalid),Map.of())).isInstanceOf(IllegalArgumentException.class);
            var source=new Definition<>("selected-default",Request.class,Request.class,List.<Node>of(new End(Terminal.SUCCEEDED,"")),null);
            var tighter=ValidatedWorkflow.compile(source,Map.of(),new DeadlinePolicy("short",Duration.ofMinutes(2)));
            var selected=runtime.start(tighter,"selected",new Request("x"));assertThat(selected.deadline()).isEqualTo(selected.admittedAt().plus(Duration.ofMinutes(2)));
        }
    }
    @Test void startInspectResumeAndIdempotencyUseOnlyLifecycleApi() throws Exception {
        var deployment=deployment(Map.of("suffix","!"));var workflow=echo(deployment,Echo.class,null);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            var admitted=runtime.start(workflow,"same",new Request("hello"),"display one");
            assertThat(admitted.status()).isEqualTo(RunSnapshot.Status.ACTIVE);
            assertThat(admitted.deadline()).isEqualTo(admitted.admittedAt().plus(Duration.ofHours(1)));
            assertThat(admitted.deadlineOrigin()).isEqualTo("DEFAULT:local-v1");
            assertThat(runtime.start(workflow,"same",new Request("hello"),"display changed").runId()).isEqualTo(admitted.runId());
            assertThat(runtime.start(workflow,"different",new Request("hello")).authoredIdentity()).isEqualTo(admitted.authoredIdentity());
            assertThatThrownBy(()->runtime.start(workflow,"same",new Request("conflict"))).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("different admitted facts");
            var completed=runtime.resume(admitted.runId(),workflow);
            assertThat(completed.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.result(admitted.runId(),workflow)).isEqualTo(new Request("hello!"));
            assertThat(completed.deadline()).isEqualTo(admitted.deadline());
            assertThat(completed.invocations()).hasSize(1);
            assertThat(completed.invocations().getFirst().deliveries()).hasSize(1);
            assertThat(runtime.resume(admitted.runId(),workflow)).isEqualTo(completed);
            assertThat(runtime.cancel(admitted.runId(),"owner","late").status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
        }
    }
    @Test void o01UsesSavedInputIdentityAndGenericValuesRoundTrip() throws Exception {
        var deployment=deployment(Map.of());var workflow=o01(deployment);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            var run=runtime.start(workflow,"o01",new Request("original"));
            var result=runtime.resume(run.runId(),workflow);
            assertThat(runtime.result(run.runId(),workflow)).isEqualTo(new Reply("first:original/original/changed-first"));
            String secondInput=result.invocations().get(1).inputValue();
            var fifth=result.values().stream().filter(v->v.valueId().equals(result.invocations().get(4).inputValue())).findFirst().orElseThrow();
            assertThat(fifth.components()).contains(secondInput);
            TypeRef<Nested<Request>> type=new TypeRef<>(){};
            var nested=single(deployment,NestedEcho.class,type.type(),type.type(),null,Terminal.SUCCEEDED);
            var items=new ArrayList<>(List.of(new Request("two")));var input=new Nested<>(new Request("one"),items);
            var generic=runtime.start(nested,"nested",input);items.add(new Request("late mutation"));
            byte[] exposed=generic.values().getFirst().payload();Arrays.fill(exposed,(byte)0);
            runtime.resume(generic.runId(),nested);
            assertThat(runtime.result(generic.runId(),nested)).isEqualTo(new Nested<>(new Request("one"),List.of(new Request("two"))));
        }
    }
    @Test void explicitFailureCancellationAndEncodeFailureNeverCommitSuccess() throws Exception {
        var deployment=deployment(Map.of());
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            for(var handler:List.of(NullOutput.class,Throws.class)) {
                var workflow=echo(deployment,handler,null);var admitted=runtime.start(workflow,handler.getName(),new Request("x"));
                var result=runtime.resume(admitted.runId(),workflow);
                assertThat(result.status()).isEqualTo(RunSnapshot.Status.FAILED);
                assertThat(result.nextOperation()).isZero();
                assertThat(result.events()).noneMatch(e->e.kind().equals("RESULT_COMMITTED"));
                assertThat(result.invocations().getFirst().inputValue()).isNotBlank();
            }
            var workflow=echo(deployment,Echo.class,null);var admitted=runtime.start(workflow,"cancel",new Request("x"));
            var cancelled=runtime.cancel(admitted.runId(),"alice","requested");
            assertThat(cancelled.reason().actor()).isEqualTo("alice");assertThat(cancelled.reason().message()).isEqualTo("requested");
            assertThat(runtime.resume(admitted.runId(),workflow).status()).isEqualTo(RunSnapshot.Status.CANCELLED);
            for(Terminal terminal:List.of(Terminal.FAILED,Terminal.CANCELLED)) {
                var explicit=single(deployment,Echo.class,Request.class,Request.class,null,terminal);
                var start=runtime.start(explicit,"explicit-"+terminal,new Request("x"));
                assertThat(runtime.resume(start.runId(),explicit).status().name()).isEqualTo(terminal.name());
            }
        }
    }
}
