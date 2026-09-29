/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.flows.steps;

import io.github.markpollack.workflow.flows.StepContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeStepTest {

    private final StepContext ctx = new StepContext("run", "invocation", "attempt", 1, java.time.Instant.MAX);

    @Test
    void ofShouldSubstituteInputInPromptTemplate() {
        ClaudeStep step = ClaudeStep.of("review this diff: {input}")
                .withClient((prompt, cwd, mcp, mode) -> "received: " + prompt);

        assertThat(step.execute(ctx, "the diff content")).isEqualTo("received: review this diff: the diff content");
    }

    @Test
    void ofShouldPassWorkingDirectoryToClient() {
        Path expectedPath = Path.of("/tmp/repo");
        ClaudeStep step = ClaudeStep.of("prompt")
                .workingDirectory(expectedPath)
                .withClient((prompt, cwd, mcp, mode) -> cwd != null ? cwd.toString() : "no-cwd");

        assertThat(step.execute(ctx, "x")).isEqualTo("/tmp/repo");
    }

    @Test
    void ofShouldPassMcpConfigsToClient() {
        ClaudeStep step = ClaudeStep.of("prompt")
                .withMcp(McpConfig.of("fs", "fs-config"))
                .withMcp(McpConfig.of("search", "search-config"))
                .withClient((prompt, cwd, mcp, mode) -> String.valueOf(mcp.size()));

        assertThat(step.execute(ctx, "x")).isEqualTo("2");
    }

    @Test
    void ofShouldPassPermissionModeToClient() {
        ClaudeStep step = ClaudeStep.of("prompt")
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .withClient((prompt, cwd, mcp, mode) -> mode.name());

        assertThat(step.execute(ctx, "x")).isEqualTo("ACCEPT_EDITS");
    }

    @Test
    void ofShouldDefaultToPermissionModeDefault() {
        ClaudeStep step = ClaudeStep.of("prompt")
                .withClient((prompt, cwd, mcp, mode) -> mode.name());

        assertThat(step.execute(ctx, "x")).isEqualTo("DEFAULT");
    }

    @Test
    void ofShouldHandleNullInputGracefully() {
        ClaudeStep step = ClaudeStep.of("analyze: {input}")
                .withClient((prompt, cwd, mcp, mode) -> prompt);

        assertThat(step.execute(ctx, null)).isEqualTo("analyze: ");
    }

    @Test
    void ofShouldPassImmutableMcpListToClient() {
        List<McpConfig> capturedList = new java.util.ArrayList<>();
        ClaudeStep step = ClaudeStep.of("prompt")
                .withMcp(McpConfig.of("server1", "config1"))
                .withClient((prompt, cwd, mcp, mode) -> {
                    capturedList.addAll(mcp);
                    return "ok";
                });

        step.execute(ctx, "x");

        assertThat(capturedList).hasSize(1);
        assertThat(capturedList.get(0).name()).isEqualTo("server1");
    }
}
