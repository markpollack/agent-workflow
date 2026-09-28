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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.AgentStep;
import io.github.markpollack.workflow.flows.AgentStepException;
import io.github.markpollack.workflow.flows.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link Step} that runs a full Claude agentic loop via the CLI subprocess.
 * <p>
 * {@code ClaudeStep} invokes the {@code claude} CLI in print mode ({@code -p}) and
 * blocks until Claude decides it is done. The subprocess returns text only —
 * tool-call traces, token counts, and cost data are discarded at the process boundary.
 *
 * <h2>When to use</h2>
 * <p>
 * Use {@code ClaudeStep} for quick, self-contained scripts where trace capture is
 * not needed. For experiments and production workflows that require tool-call
 * traces (e.g. Markov analysis), use {@link AgentClientStep} backed by a
 * {@code ClaudeAgentModel} with {@code traceDir} configured — this preserves
 * full execution traces as JSONL files and wires the trace path through the
 * workflow journal.
 *
 * <h2>Configuration</h2>
 * <pre>{@code
 * ClaudeStep.of("analyze this diff and identify concerns: {input}")
 *     .workingDirectory(repoPath)
 *     .withMcp(McpConfig.of("filesystem", fileSystemMcpServer))
 *     .withMcp(McpConfig.of("search", codeSearchMcpServer))
 *     .withA2a(A2aEndpoint.of("security-reviewer", securityAgentUrl))
 *     .permissionMode(PermissionMode.ACCEPT_EDITS)
 * }</pre>
 *
 * <h2>Template substitution</h2>
 * <p>
 * The prompt template may contain {@code {input}} which is replaced with the
 * step's input value (via {@code toString()}) before execution.
 *
 * <h2>Testing</h2>
 * <p>
 * Inject a {@link ClaudeSyncClient} via {@link #withClient(ClaudeSyncClient)} to
 * replace the subprocess invocation with a mock or stub.
 */
public class ClaudeStep implements Step<String, String>, AgentStep {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeStep.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String promptTemplate;
    private Path workingDirectory;
    private final List<McpConfig> mcpConfigs = new ArrayList<>();
    private final List<A2aEndpoint> a2aEndpoints = new ArrayList<>();
    private PermissionMode permissionMode = PermissionMode.DEFAULT;
    private ClaudeSyncClient client;

    private ClaudeStep(String promptTemplate) {
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
    }

    /**
     * Creates a new {@code ClaudeStep} with the given prompt template.
     * <p>
     * Use {@code {input}} in the template to substitute the step's runtime input.
     *
     * @param promptTemplate the prompt template (may contain {@code {input}})
     * @return a new ClaudeStep builder
     */
    public static ClaudeStep of(String promptTemplate) {
        return new ClaudeStep(promptTemplate);
    }

    /**
     * Sets the working directory for the Claude CLI session.
     *
     * @param workingDirectory the directory Claude should operate in
     * @return this step (for chaining)
     */
    public ClaudeStep workingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    /**
     * Adds an MCP server configuration available to Claude during its loop.
     *
     * @param mcpConfig the MCP server configuration
     * @return this step (for chaining)
     */
    public ClaudeStep withMcp(McpConfig mcpConfig) {
        this.mcpConfigs.add(Objects.requireNonNull(mcpConfig, "mcpConfig must not be null"));
        return this;
    }

    /**
     * Adds an A2A peer agent endpoint Claude can delegate sub-tasks to.
     *
     * @param endpoint the A2A peer agent endpoint
     * @return this step (for chaining)
     */
    public ClaudeStep withA2a(A2aEndpoint endpoint) {
        this.a2aEndpoints.add(Objects.requireNonNull(endpoint, "endpoint must not be null"));
        return this;
    }

    /**
     * Sets the permission mode controlling how Claude handles tool use permissions.
     *
     * @param mode the permission mode
     * @return this step (for chaining)
     */
    public ClaudeStep permissionMode(PermissionMode mode) {
        this.permissionMode = Objects.requireNonNull(mode, "mode must not be null");
        return this;
    }

    /**
     * Injects a custom {@link ClaudeSyncClient}, replacing the default subprocess invocation.
     * <p>
     * Use for testing or when integrating with a {@code claude-agent-sdk-java} library.
     *
     * @param client the client to use
     * @return this step (for chaining)
     */
    public ClaudeStep withClient(ClaudeSyncClient client) {
        this.client = client;
        return this;
    }

    @Override
    public String name() {
        return "ClaudeStep";
    }

    @Override
    public String execute(StepContext ctx, String input) {
        String resolved = promptTemplate.replace("{input}", input != null ? input : "");
        if (client != null) {
            return client.run(resolved, workingDirectory, List.copyOf(mcpConfigs), permissionMode);
        }
        return runSubprocess(resolved);
    }

    private String runSubprocess(String prompt) {
        List<String> command = buildCommand(prompt);
        logger.debug("Executing Claude CLI: {}", command);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new AgentStepException("Claude CLI exited with code " + exitCode + ": " + output.trim());
            }
            return output.trim();
        } catch (IOException e) {
            throw new AgentStepException("Failed to start Claude CLI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentStepException("Claude CLI execution interrupted", e);
        }
    }

    private List<String> buildCommand(String prompt) {
        List<String> command = new ArrayList<>();
        command.add("claude");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("text");

        if (!mcpConfigs.isEmpty()) {
            try {
                Path mcpConfigFile = writeMcpConfigFile();
                command.add("--mcp-config");
                command.add(mcpConfigFile.toString());
            } catch (IOException e) {
                throw new AgentStepException("Failed to write MCP config file", e);
            }
        }

        switch (permissionMode) {
            case ACCEPT_EDITS -> {
                command.add("--allowedTools");
                command.add("Bash,Write,Edit");
            }
            case BYPASS_PERMISSIONS -> command.add("--dangerously-skip-permissions");
            default -> { /* DEFAULT: no additional flags */ }
        }

        return command;
    }

    private Path writeMcpConfigFile() throws IOException {
        Map<String, Object> configMap = new LinkedHashMap<>();
        for (McpConfig config : mcpConfigs) {
            configMap.put(config.name(), config.serverConfig());
        }
        Path tempFile = Files.createTempFile("agent-flows-mcp-", ".json");
        tempFile.toFile().deleteOnExit();
        MAPPER.writeValue(tempFile.toFile(), configMap);
        logger.debug("Wrote MCP config to: {}", tempFile);
        return tempFile;
    }
}
