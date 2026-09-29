package io.github.markpollack.workflow.flows.steps;

import io.github.markpollack.workflow.flows.StepContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises ClaudeStep's real subprocess path
 * (no mock client). Requires the {@code claude} CLI on PATH and a
 * valid authentication session (Claude Max or API key).
 * <p>
 * Enable via: {@code CLAUDE_STEP_IT=true ./mvnw verify}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "CLAUDE_STEP_IT", matches = "true")
class ClaudeStepIntegrationTest {

    private final StepContext ctx = new StepContext("run", "invocation", "attempt", 1, java.time.Instant.MAX);

    @Test
    void subprocessRunsWithWorkingDirectory(@TempDir Path tempDir) {
        ClaudeStep step = ClaudeStep.of("Reply with only the word 'hello'. Nothing else.")
                .workingDirectory(tempDir)
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS);

        String result = step.execute(ctx, "");

        assertThat(result).isNotBlank();
        assertThat(result.toLowerCase()).contains("hello");
    }

}
