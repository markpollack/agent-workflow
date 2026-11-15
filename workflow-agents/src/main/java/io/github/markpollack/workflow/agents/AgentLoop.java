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
package io.github.markpollack.workflow.agents;

import io.micrometer.observation.ObservationRegistry;
import io.github.markpollack.workflow.callback.AgentCallback;
import io.github.markpollack.workflow.patterns.ToolCallListener;
import io.github.markpollack.workflow.patterns.advisor.AgentLoopAdvisor;
import io.github.markpollack.workflow.patterns.advisor.AgentLoopListener;
import io.github.markpollack.workflow.patterns.advisor.AgentLoopTerminatedException;
import io.github.markpollack.workflow.patterns.observation.ToolCallObservationHandler;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.workflow.core.TerminationReason;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import io.github.markpollack.workflow.tools.BashTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.model.tool.DefaultToolCallingManager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AgentLoop - A ready-to-use SWE agent leveraging Spring AI's built-in agent loop.
 * <p>
 * Spring AI's ChatClient + {@link AgentLoopAdvisor} handles the entire tool execution loop.
 * This class adds: tools, observability wiring, session memory, and a simple API.
 * <p>
 * Features:
 * <ul>
 *   <li><strong>Session memory</strong>: Optional multi-turn conversation support</li>
 *   <li><strong>Interactive mode</strong>: Enables AskUserQuestionTool for human-in-the-loop</li>
 *   <li><strong>Callbacks</strong>: AgentCallback for TUI integration</li>
 * </ul>
 *
 * @see Config for configuration options
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final Config config;
    private final ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final ToolCallObservationHandler observationHandler;
    private final CountingToolCallListener countingListener;
    private final ChatMemory sessionMemory;
    private final boolean interactive;
    private final String conversationId;

    private AgentLoop(Builder builder) {
        this.config = builder.config;
        this.sessionMemory = builder.sessionMemory;
        this.interactive = builder.interactive;
        this.conversationId = builder.conversationId != null ? builder.conversationId : "default";

        // Create tools - mix of spring-ai-agent-utils and workflow-tools
        // Use workflow-tools BashTool instead of ShellTools to avoid overly verbose tool descriptions
        // Pass workingDirectory to tools that support it so they operate within the sandbox context

        // Tools with @Tool annotated methods - convert via ToolCallbacks.from()
        List<Object> annotatedToolObjects = new ArrayList<>();
        annotatedToolObjects.add(FileSystemTools.builder().build());
        annotatedToolObjects.add(new BashTool(config.workingDirectory(), config.commandTimeout()));
        annotatedToolObjects.add(GlobTool.builder()
                .workingDirectory(config.workingDirectory())
                .build());
        annotatedToolObjects.add(GrepTool.builder()
                .workingDirectory(config.workingDirectory())
                .build());
        annotatedToolObjects.add(new SubmitTool());
        annotatedToolObjects.add(TodoWriteTool.builder().build());

        // Add AskUserQuestionTool if interactive mode and callback provided.
        // The tool's Question schema is mapped onto the workflow-api Question
        // record so the AgentCallback SPI stays free of tool-library types.
        if (interactive && builder.agentCallback != null) {
            annotatedToolObjects.add(AskUserQuestionTool.builder()
                    .questionHandler(questions -> builder.agentCallback.onQuestion(toCallbackQuestions(questions)))
                    .build());
        }

        // Tools that directly implement ToolCallback - add directly to callback list
        List<ToolCallback> directCallbacks = new ArrayList<>();

        // Add TaskTool for sub-agent delegation (returns ToolCallback directly)
        var taskRepository = new DefaultTaskRepository();
        var claudeSubagentType = ClaudeSubagentType.builder()
                .chatClientBuilder("default", ChatClient.builder(builder.model))
                .build();
        directCallbacks.add(TaskTool.builder()
                .taskRepository(taskRepository)
                .subagentTypes(claudeSubagentType)
                .build());

        // Convert @Tool annotated objects to ToolCallbacks and merge with direct callbacks
        var annotatedCallbacks = ToolCallbacks.from(annotatedToolObjects.toArray());
        var allCallbacks = new ArrayList<>(Arrays.asList(annotatedCallbacks));
        allCallbacks.addAll(directCallbacks);
        this.tools = List.copyOf(allCallbacks);

        // Wrap listener in counting listener for toolCallsExecuted tracking
        ToolCallListener baseListener = builder.toolCallListener != null
                ? builder.toolCallListener
                : new LoggingToolCallListener();
        this.countingListener = new CountingToolCallListener(baseListener);

        // Wire observability: ObservationRegistry → ToolCallObservationHandler → ToolCallListener
        this.observationHandler = ToolCallObservationHandler.of(countingListener);
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(observationHandler);

        // Create ChatClient with advisors
        var toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(registry)
                .build();

        // Build AgentLoopAdvisor with optional listener bridge
        var advisorBuilder = AgentLoopAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .maxTurns(config.maxTurns());

        if (builder.agentCallback != null) {
            advisorBuilder.listener(new CallbackLoopListener(builder.agentCallback));
        }

        var toolCallAdvisor = advisorBuilder.build();

        // Build ChatClient with optional memory advisor
        // Note: defaultToolContext is required for tools that use ToolContext (e.g., FileSystemTools)
        // tools list already contains ToolCallback instances
        var chatClientBuilder = ChatClient.builder(builder.model)
                .defaultAdvisors(toolCallAdvisor)
                .defaultToolCallbacks(tools.toArray(new ToolCallback[0]))
                .defaultToolContext(Map.of("agentId", "agent-loop"));

        if (sessionMemory != null) {
            var memoryAdvisor = MessageChatMemoryAdvisor.builder(sessionMemory)
                    .build();
            chatClientBuilder.defaultAdvisors(memoryAdvisor)
                    .defaultAdvisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        }

        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Map the AskUserQuestionTool's question schema onto the tool-agnostic
     * workflow-api {@link io.github.markpollack.workflow.callback.Question}
     * record used by the {@link AgentCallback} SPI.
     */
    private static List<io.github.markpollack.workflow.callback.Question> toCallbackQuestions(
            List<AskUserQuestionTool.Question> questions) {
        return questions.stream()
                .map(q -> new io.github.markpollack.workflow.callback.Question(
                        q.question(), q.header(),
                        q.options().stream()
                                .map(o -> new io.github.markpollack.workflow.callback.Question.Option(
                                        o.label(), o.description()))
                                .toList(),
                        q.multiSelect()))
                .toList();
    }

    /**
     * Run the agent with the given task (single-task mode).
     * <p>
     * If session memory is configured, the conversation history is preserved
     * across multiple run() calls.
     */
    public Result run(String task) {
        log.info("AgentLoop starting: {}", truncate(task, 80));
        countingListener.reset();
        observationHandler.setContext("agent-loop", 1);

        try {
            // Include working directory in system prompt so LLM uses correct paths
            String systemPromptWithWorkdir = config.systemPrompt() +
                    "\n\nYour working directory is: " + config.workingDirectory().toAbsolutePath();

            ChatResponse response = chatClient.prompt()
                    .system(systemPromptWithWorkdir)
                    .user(task)
                    .call()
                    .chatResponse();

            long tokens = extractTokens(response);
            String output = extractText(response);
            int toolCalls = countingListener.getToolCallCount();
            log.info("AgentLoop completed: {} tokens, {} tool calls", tokens, toolCalls);

            return new Result("COMPLETED", output, 1, toolCalls, tokens, tokens * 0.000006);

        } catch (AgentLoopTerminatedException e) {
            var state = e.getState();
            log.warn("AgentLoop terminated: {} at turn {}", e.getReason(), state != null ? state.currentTurn() : 0);
            int toolCalls = countingListener.getToolCallCount();
            String status = switch (e.getReason()) {
                case MAX_TURNS_REACHED -> "TURN_LIMIT_REACHED";
                case TIMEOUT -> "TIMEOUT";
                case COST_LIMIT_EXCEEDED -> "COST_LIMIT_EXCEEDED";
                case STUCK_DETECTED -> "STUCK";
                case EXTERNAL_SIGNAL -> "ABORTED";
                default -> "TERMINATED";
            };
            return new Result(status, e.getPartialOutput(),
                    state != null ? state.currentTurn() : 0, toolCalls,
                    state != null ? state.totalTokensUsed() : 0,
                    state != null ? state.estimatedCost() : 0.0);
        }
    }

    /**
     * Chat with the agent (multi-turn interactive mode).
     * <p>
     * Unlike run(), this method is designed for interactive TUI use:
     * - Callbacks are invoked for thinking, tool calls, and responses
     * - Session memory preserves conversation across calls
     * - Questions are routed to the callback for user interaction
     *
     * @param message User message
     * @param callback Callback for events (must be same as builder callback)
     * @return Agent result
     */
    public Result chat(String message, AgentCallback callback) {
        // Emit the first onThinking explicitly: Spring AI 2.0 GA's tool-loop does not invoke the
        // per-turn advisor hook (onTurnStarted) for a no-tool response, so it would otherwise be
        // missed. Subsequent tool-execution turns still signal via CallbackLoopListener.onTurnStarted().
        if (callback != null) {
            callback.onThinking();
        }
        Result result = run(message);
        if (callback != null) {
            callback.onComplete();
        }
        return result;
    }

    /**
     * Clear session memory, starting a fresh conversation.
     */
    public void clearSession() {
        if (sessionMemory != null) {
            sessionMemory.clear(conversationId);
            log.debug("Session cleared for conversation: {}", conversationId);
        }
    }

    /**
     * Check if session memory is enabled.
     */
    public boolean hasSessionMemory() {
        return sessionMemory != null;
    }

    /**
     * Check if interactive mode is enabled.
     */
    public boolean isInteractive() {
        return interactive;
    }

    private long extractTokens(ChatResponse r) {
        if (r == null || r.getMetadata() == null || r.getMetadata().getUsage() == null) return 0;
        var t = r.getMetadata().getUsage().getTotalTokens();
        return t != null ? t : 0;
    }

    private String extractText(ChatResponse r) {
        return r != null && r.getResult() != null ? r.getResult().getOutput().getText() : null;
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // --- Static factory methods for backwards compatibility ---

    /**
     * Create an AgentLoop with default configuration.
     * @deprecated Use {@link #builder()} instead
     */
    @Deprecated
    public AgentLoop(Config config, ChatModel model) {
        this(builder().config(config).model(model));
    }

    /**
     * Create an AgentLoop with a custom tool listener.
     * @deprecated Use {@link #builder()} instead
     */
    @Deprecated
    public AgentLoop(Config config, ChatModel model, ToolCallListener listener) {
        this(builder().config(config).model(model).toolCallListener(listener));
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Config config;
        private ChatModel model;
        private ChatMemory sessionMemory;
        private boolean interactive = false;
        private AgentCallback agentCallback;
        private ToolCallListener toolCallListener;
        private String conversationId;

        private Builder() {}

        /**
         * Set the agent configuration (required).
         */
        public Builder config(Config config) {
            this.config = config;
            return this;
        }

        /**
         * Set the chat model (required).
         */
        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        /**
         * Enable session memory for multi-turn conversations.
         * <p>
         * If null (default), each run() is independent with no history.
         * If provided, conversation history is preserved across calls.
         */
        public Builder sessionMemory(ChatMemory sessionMemory) {
            this.sessionMemory = sessionMemory;
            return this;
        }

        /**
         * Enable session memory with default in-memory implementation.
         */
        public Builder sessionMemory() {
            this.sessionMemory = MessageWindowChatMemory.builder().build();
            return this;
        }

        /**
         * Enable interactive mode with AskUserQuestionTool.
         * <p>
         * When true and agentCallback is provided, the agent can ask
         * the user questions during execution via onQuestion().
         */
        public Builder interactive(boolean interactive) {
            this.interactive = interactive;
            return this;
        }

        /**
         * Set callback for agent events (TUI integration).
         * <p>
         * Required for interactive mode to handle questions.
         */
        public Builder agentCallback(AgentCallback agentCallback) {
            this.agentCallback = agentCallback;
            return this;
        }

        /**
         * Set callback for tool call events.
         */
        public Builder toolCallListener(ToolCallListener toolCallListener) {
            this.toolCallListener = toolCallListener;
            return this;
        }

        /**
         * Set conversation ID for session memory.
         * <p>
         * Defaults to "default" if not specified.
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public AgentLoop build() {
            if (config == null) {
                throw new IllegalStateException("config is required");
            }
            if (model == null) {
                throw new IllegalStateException("model is required");
            }
            if (interactive && agentCallback == null) {
                log.warn("Interactive mode enabled but no agentCallback provided - questions will not be handled");
            }
            return new AgentLoop(this);
        }
    }

    /**
     * Bridges AgentLoopListener events to AgentCallback.
     */
    private static class CallbackLoopListener implements AgentLoopListener {
        private final AgentCallback callback;

        CallbackLoopListener(AgentCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onLoopStarted(String runId, String userMessage) {
            // AgentCallback doesn't have a direct equivalent
        }

        @Override
        public void onTurnStarted(String runId, int turn) {
            // Fires for each tool-execution turn (turn 2+). The first "thinking" signal is
            // emitted by chat() because Spring AI 2.0 GA's tool-loop does not invoke this hook
            // for a single no-tool response.
            callback.onThinking();
        }

        @Override
        public void onTurnCompleted(String runId, int turn, TerminationReason reason) {
            // Handled in onLoopCompleted
        }

        @Override
        public void onLoopCompleted(String runId, LoopState state, TerminationReason reason) {
            callback.onComplete();
        }

        @Override
        public void onLoopFailed(String runId, LoopState state, Throwable error) {
            callback.onError(error);
        }
    }

    /**
     * Result of an AgentLoop execution.
     *
     * @param status Status of the execution (COMPLETED, TURN_LIMIT_REACHED, FAILED)
     * @param output The agent's final output (may be partial if turn limit reached)
     * @param turnsCompleted Number of turns (LLM call + tool execution cycles) completed
     * @param toolCallsExecuted Number of tool calls executed across all turns
     * @param totalTokens Total tokens used
     * @param estimatedCost Estimated cost in dollars
     */
    public record Result(
            String status,
            String output,
            int turnsCompleted,
            int toolCallsExecuted,
            long totalTokens,
            double estimatedCost
    ) {
        public boolean isSuccess() { return "COMPLETED".equals(status); }
        public boolean isFailure() { return "FAILED".equals(status); }
        public boolean isTurnLimitReached() { return "TURN_LIMIT_REACHED".equals(status); }
    }

    /**
     * Configuration for AgentLoop.
     * <p>
     * Provides sensible defaults for system prompt, turn limits, cost limits,
     * command timeout, and working directory.
     */
    public record Config(
            String systemPrompt,
            int maxTurns,
            double costLimit,
            Duration commandTimeout,
            Path workingDirectory
    ) {
        private static final String DEFAULT_SYSTEM_PROMPT = """
                You are an autonomous AI assistant that solves software engineering tasks.

                You have access to the following tools:
                - Read: Read file contents (use absolute paths)
                - Write: Create or overwrite files (use absolute paths)
                - Edit: Make targeted edits to existing files
                - LS: List directory contents
                - Bash: Execute shell commands
                - Glob: Find files by pattern
                - Grep: Search file contents
                - TodoWrite: Track progress on multi-step tasks
                - Task: Delegate to specialized sub-agents for complex exploration
                - Submit: Submit your final answer when the task is complete

                When you have completed the task, use the Submit tool to provide your final answer.

                ## Task Planning and Tracking (REQUIRED)

                REQUIRED: Use TodoWrite to organize and track your work throughout the session.

                When to use TodoWrite:
                - Before starting any task that involves multiple steps or files
                - When the user provides a list of things to do
                - When you need to explore, read, modify, and verify code
                - Whenever you are uncertain about the scope of work

                How to use TodoWrite:
                - Create your task list BEFORE you begin working
                - Mark each task as in_progress when you start it (only one at a time)
                - Mark tasks as completed immediately after finishing
                - Add new tasks if you discover additional work needed

                When in doubt, create a todo list. Being organized ensures thoroughness.

                ## Codebase Exploration (REQUIRED)

                REQUIRED: For exploring or investigating a codebase, use Task with subagent_type=Explore.

                Use Task/Explore when:
                - Searching for where something is implemented
                - Understanding how code is structured
                - Finding files related to a feature or component
                - Investigating unfamiliar parts of the codebase

                Do NOT use bash find/grep for exploration - use Task with subagent_type=Explore instead.

                ## Tool Selection Rules

                IMPORTANT: Bash is for terminal operations like git, npm, docker, javac, java.
                DO NOT use bash for file operations - use the specialized tools instead.

                Avoid using Bash with `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands.
                Always prefer dedicated tools:
                - File search: use Glob (NOT find or ls)
                - Content search: use Grep (NOT grep or rg)
                - Read files: use Read (NOT cat/head/tail)
                - Edit files: use Edit (NOT sed/awk)
                - Write files: use Write (NOT echo >/cat <<EOF)

                ## Verification

                - When creating complete Java classes, verify they compile with javac
                - After fixing bugs, run the code or tests to confirm the fix works
                - Use judgment: skip verification for fragments or partial code

                ## Other Guidelines

                - All file paths must be absolute paths
                - Execute one operation at a time
                - Check output before proceeding
                - If an operation fails, analyze the error and try a different approach
                """;

        private static final int DEFAULT_MAX_TURNS = 20;
        private static final double DEFAULT_COST_LIMIT = 1.0;
        private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

        public Config {
            if (maxTurns <= 0) {
                throw new IllegalArgumentException("maxTurns must be positive");
            }
            if (costLimit <= 0) {
                throw new IllegalArgumentException("costLimit must be positive");
            }
            if (commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero()) {
                throw new IllegalArgumentException("commandTimeout must be positive");
            }
            if (workingDirectory == null) {
                throw new IllegalArgumentException("workingDirectory cannot be null");
            }
        }

        public static ConfigBuilder builder() {
            return new ConfigBuilder();
        }

        public ConfigBuilder toBuilder() {
            return new ConfigBuilder()
                    .systemPrompt(this.systemPrompt)
                    .maxTurns(this.maxTurns)
                    .costLimit(this.costLimit)
                    .commandTimeout(this.commandTimeout)
                    .workingDirectory(this.workingDirectory);
        }

        public Config apply(Consumer<ConfigBuilder> customizer) {
            ConfigBuilder builder = toBuilder();
            customizer.accept(builder);
            return builder.build();
        }

        public static final class ConfigBuilder {
            private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
            private int maxTurns = DEFAULT_MAX_TURNS;
            private double costLimit = DEFAULT_COST_LIMIT;
            private Duration commandTimeout = DEFAULT_COMMAND_TIMEOUT;
            private Path workingDirectory;

            public ConfigBuilder systemPrompt(String systemPrompt) {
                this.systemPrompt = systemPrompt;
                return this;
            }

            public ConfigBuilder maxTurns(int maxTurns) {
                this.maxTurns = maxTurns;
                return this;
            }

            public ConfigBuilder costLimit(double costLimit) {
                this.costLimit = costLimit;
                return this;
            }

            public ConfigBuilder commandTimeout(Duration commandTimeout) {
                this.commandTimeout = commandTimeout;
                return this;
            }

            public ConfigBuilder workingDirectory(Path workingDirectory) {
                this.workingDirectory = workingDirectory;
                return this;
            }

            public Config build() {
                if (workingDirectory == null) {
                    workingDirectory = Path.of(System.getProperty("user.dir"));
                }
                return new Config(
                        systemPrompt,
                        maxTurns,
                        costLimit,
                        commandTimeout,
                        workingDirectory
                );
            }
        }
    }

    /**
     * Tool for submitting the final answer and completing the task.
     * <p>
     * Using returnDirect=true means the result is returned directly to the user
     * without going back to the model, effectively terminating the agent loop.
     */
    private static class SubmitTool {

        private static final Logger log = LoggerFactory.getLogger(SubmitTool.class);

        @org.springframework.ai.tool.annotation.Tool(
            name = "Submit",
            description = "Submit your final answer when the task is complete. This ends the conversation.",
            returnDirect = true)
        public String submit(
                @org.springframework.ai.tool.annotation.ToolParam(
                    description = "The final answer or result of the task") String answer) {
            log.info("Task submitted with answer: {}", answer != null && answer.length() > 100
                    ? answer.substring(0, 100) + "..." : answer);
            return answer;
        }
    }
}
