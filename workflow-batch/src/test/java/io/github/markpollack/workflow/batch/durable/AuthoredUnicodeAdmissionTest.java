package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.markpollack.workflow.flows.compiler.DeadlinePolicy;
import io.github.markpollack.workflow.flows.compiler.StructuredWorkflowCompiler;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class AuthoredUnicodeAdmissionTest {
    @TempDir Path directory;
    public static class Echo implements Step<String,String> {
        static final AtomicInteger calls=new AtomicInteger();
        public String execute(StepContext context,String input) {
            calls.incrementAndGet();return input;
        }
    }
    private static ApplicationDeployment deployment() {
        return new ApplicationDeployment("authored-unicode","immutable-build",Map.of(),Map.of(Echo.class.getName(),new Echo()));
    }
    private static ValidatedWorkflow workflow(ApplicationDeployment deployment,String name,String call,String reason,String profile) {
        var definition=new Definition<>(name,String.class,String.class,
                List.of(new Call(call,Op.declared("work",String.class,String.class)),new End(Terminal.SUCCEEDED,reason)),Duration.ofMinutes(2));
        var placement=StructuredWorkflowCompiler.compile(definition).bindings().getFirst().placement();
        return ValidatedWorkflow.compile(definition,Map.of(placement,deployment.selection(Echo.class.getName(),String.class,String.class)),
                new DeadlinePolicy(profile,Duration.ofHours(1)));
    }
    private static ValidatedWorkflow changed(ApplicationDeployment deployment,int field,String text) {
        return workflow(deployment,field==0?text:"?",field==1?text:"work",field==2?text:"",field==3?text:"local-v1");
    }

    @Test void malformedDefinitionCannotReachAdmissionOrResumeAndRefusalHasNoEffects() throws Exception {
        Echo.calls.set(0);var deployment=deployment();Path file=directory.resolve("runs");
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            // The first definition from the former admission/resume collision cannot be admitted.
            assertThatThrownBy(()->runtime.start(changed(deployment,0,"\uD800"),"original","root"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("well-formed Unicode");
            assertThat(runtime.discover()).isEmpty();assertThat(Echo.calls.get()).isZero();
            var valid=changed(deployment,0,"?");var run=runtime.start(valid,"question","root");
            String before=state(file,run.runId());
            for(int field=0;field<4;field++) {
                final int selected=field;
                for(String malformed:List.of("\uD800","\uDC00","x\uD800y","x\uDC00y","\uDC00\uD800","\uD83D\uDE00\uD800")) {
                    assertThatThrownBy(()->runtime.start(changed(deployment,selected,malformed),"refused","root"))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("well-formed Unicode");
                    assertThatThrownBy(()->runtime.resume(run.runId(),changed(deployment,selected,malformed)))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("well-formed Unicode");
                    assertThat(state(file,run.runId())).isEqualTo(before);assertThat(Echo.calls.get()).isZero();
                }
            }
            assertThat(runtime.discover()).hasSize(1);evidence(file,run.runId(),"unicode-refusal-no-progress");
            assertThat(runtime.resume(run.runId(),valid).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.result(run.runId(),valid)).isEqualTo("root");assertThat(Echo.calls.get()).isEqualTo(1);
        }
    }

    @Test void questionAndSupplementaryNamesStayDistinctAcrossActiveAndTerminalCompatibility() throws Exception {
        Echo.calls.set(0);var deployment=deployment();Path file=directory.resolve("runs");
        var question=changed(deployment,0,"?");var supplementary=changed(deployment,0,"\uD83D\uDE00");
        assertThat(question.authoredIdentity()).isNotEqualTo(supplementary.authoredIdentity());
        String questionId;
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            var run=runtime.start(question,"question","root");questionId=run.runId();String before=state(file,questionId);
            assertThatThrownBy(()->runtime.resume(questionId,supplementary)).isInstanceOf(WorkflowRefusal.class);
            assertThat(state(file,questionId)).isEqualTo(before);assertThat(Echo.calls.get()).isZero();
        }
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            runtime.resume(questionId,question);String terminal=state(file,questionId);
            assertThatThrownBy(()->runtime.resume(questionId,supplementary)).isInstanceOf(WorkflowRefusal.class);
            assertThatThrownBy(()->runtime.result(questionId,supplementary)).isInstanceOf(WorkflowRefusal.class);
            assertThat(state(file,questionId)).isEqualTo(terminal);assertThat(Echo.calls.get()).isEqualTo(1);
            var other=runtime.start(supplementary,"supplementary","root");runtime.resume(other.runId(),supplementary);
            assertThat(runtime.result(other.runId(),supplementary)).isEqualTo("root");assertThat(Echo.calls.get()).isEqualTo(2);
        }
    }
}
