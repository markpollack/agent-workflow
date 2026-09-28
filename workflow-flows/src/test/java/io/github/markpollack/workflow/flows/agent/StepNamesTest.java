package io.github.markpollack.workflow.flows.agent;

import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.core.StepName;
import io.github.markpollack.workflow.flows.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StepNames")
class StepNamesTest {

    @StepName("stable-analyze")
    static class AnnotatedStep implements Step<String, String> {
        @Override
        public String execute(StepContext ctx, String input) {
            return input;
        }

        @Override
        public String name() {
            return "AnnotatedStep";
        }
    }

    static class PlainStep implements Step<String, String> {
        @Override
        public String execute(StepContext ctx, String input) {
            return input;
        }

        @Override
        public String name() {
            return "plain-step";
        }
    }

    @Test
    void shouldReturnAnnotationValueWhenStepNamePresent() {
        Step<String, String> step = new AnnotatedStep();

        String resolved = StepNames.resolve(step);

        assertThat(resolved).isEqualTo("stable-analyze");
    }

    @Test
    void shouldFallBackToStepNameMethod() {
        Step<String, String> step = new PlainStep();

        String resolved = StepNames.resolve(step);

        assertThat(resolved).isEqualTo("plain-step");
    }
}
