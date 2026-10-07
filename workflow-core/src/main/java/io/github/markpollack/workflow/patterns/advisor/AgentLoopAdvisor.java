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
package io.github.markpollack.workflow.patterns.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.workflow.core.TerminationReason;
import io.github.markpollack.workflow.patterns.judge.SpringAiJuryAdapter;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified agent loop advisor with comprehensive control features.
 * <p>
 * Consolidates turn limiting, cost tracking, timeout, abort signals,
 * stuck detection, listeners, and optional jury evaluation into a
 * single advisor that leverages Spring AI's recursive tool calling.
 * <p>
 * This advisor extends {@link ToolCallAdvisor} and hooks into its
 * recursive tool calling loop via:
 * <ul>
 *   <li>{@code doInitializeLoop()} - Reset state, start timer, notify listeners</li>
 *   <li>{@code doBeforeCall()} - Check termination conditions before each LLM call</li>
 *   <li>{@code doAfterCall()} - Track metrics, evaluate jury, notify listeners</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong>
 * <pre>{@code
 * var advisor = AgentLoopAdvisor.builder()
 *     .toolCallingManager(manager)
 *     .maxTurns(20)
 *     .timeout(Duration.ofMinutes(5))
 *     .costLimit(1.0)
 *     .listener(myListener)
 *     .build();
 *
 * var chatClient = ChatClient.builder(model)
 *     .defaultAdvisors(advisor)
 *     .build();
 * }</pre>
 *
 * @see AgentLoopConfig for configuration options
 * @see AgentLoopListener for event handling
 */
public class AgentLoopAdvisor extends ToolCallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopAdvisor.class);

    private final AgentLoopConfig config;
    private final List<AgentLoopListener> listeners;
    private final AtomicBoolean abortSignal;
    private final Jury jury;
    private final SpringAiJuryAdapter juryAdapter;
    private final Path workingDirectory;

    // Thread-local state for concurrent safety
    private final ThreadLocal<LoopState> loopState = new ThreadLocal<>();
    private final ThreadLocal<String> userMessage = new ThreadLocal<>();

    protected AgentLoopAdvisor(Builder builder) {
        super(builder.toolCallingManager, builder.getToolExecutionEligibilityChecker(), builder.advisorOrder, true);
        this.config = new AgentLoopConfig(
                builder.maxTurns,
                builder.timeout,
                builder.costLimit,
                builder.stuckThreshold,
                builder.juryEvaluationInterval
        );
        this.listeners = new ArrayList<>(builder.listeners);
        this.abortSignal = new AtomicBoolean(false);
        this.jury = builder.jury;
        this.workingDirectory = builder.workingDirectory;

        // Create jury adapter if jury configured
        if (jury != null) {
            this.juryAdapter = new SpringAiJuryAdapter(jury, "agent-loop-jury");
        } else {
            this.juryAdapter = null;
        }
    }

    @Override
    public String getName() {
        return "Agent Loop Advisor";
    }

    // --- ToolCallAdvisor hooks ---

    @Override
    protected ChatClientRequest doInitializeLoop(ChatClientRequest request, CallAdvisorChain chain) {
        // Initialize fresh state for this run
        String runId = UUID.randomUUID().toString();
        LoopState initialState = LoopState.initial(runId);
        loopState.set(initialState);

        // Extract and store user message for listeners
        String message = extractUserMessage(request);
        userMessage.set(message);

        // Reset abort signal
        abortSignal.set(false);

        log.debug("Loop initialized: runId={}, maxTurns={}, timeout={}",
                runId, config.maxTurns(), config.timeout());

        // Notify listeners
        notifyLoopStarted(runId, message);

        return super.doInitializeLoop(request, chain);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        LoopState state = loopState.get();
        if (state == null) {
            // Shouldn't happen, but be defensive
            log.warn("Loop state not initialized, creating new state");
            state = LoopState.initial(UUID.randomUUID().toString());
            loopState.set(state);
        }

        // Check termination conditions BEFORE each LLM call
        checkAbortSignal(state);
        checkTimeout(state);
        checkCostLimit(state);
        checkMaxTurns(state);

        // Notify turn starting
        notifyTurnStarted(state.runId(), state.currentTurn());

        log.debug("Turn {} starting for run {}", state.currentTurn() + 1, state.runId());

        return super.doBeforeCall(request, chain);
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        LoopState state = loopState.get();
        if (state == null) {
            return super.doAfterCall(response, chain);
        }

        // Update metrics
        long tokens = extractTokensUsed(response);
        double cost = estimateCost(tokens);
        int outputSignature = computeOutputSignature(response);
        boolean hasToolCalls = hasToolCalls(response);

        LoopState newState = state.completeTurn(tokens, cost, hasToolCalls, outputSignature);
        loopState.set(newState);

        int completedTurn = state.currentTurn();
        log.debug("Turn {} completed: tokens={}, cost=${}, hasToolCalls={}",
                completedTurn + 1, tokens, String.format("%.4f", cost), hasToolCalls);

        // Check stuck detection
        checkStuckDetection(newState, response);

        // Optional jury evaluation
        if (shouldEvaluateJury(newState)) {
            ChatResponse chatResponse = response.chatResponse();
            Verdict verdict = juryAdapter.evaluate(newState, chatResponse, workingDirectory);
            if (verdict.judgment().pass()) {
                log.info("Jury passed at turn {}", completedTurn + 1);
                notifyTurnCompleted(state.runId(), completedTurn, TerminationReason.SCORE_THRESHOLD_MET);
                notifyLoopCompleted(state.runId(), newState, TerminationReason.SCORE_THRESHOLD_MET);
                throw new JuryPassedException(verdict, newState, response);
            }
        }

        // Notify turn completed (no termination)
        notifyTurnCompleted(state.runId(), completedTurn, null);

        return super.doAfterCall(response, chain);
    }

    // --- Termination checks ---

    private void checkAbortSignal(LoopState state) {
        if (abortSignal.get() || state.abortSignalled()) {
            log.info("Abort signal received for run {}", state.runId());
            notifyLoopCompleted(state.runId(), state, TerminationReason.EXTERNAL_SIGNAL);
            throw new AgentLoopTerminatedException(
                    TerminationReason.EXTERNAL_SIGNAL,
                    "Abort signal received",
                    state
            );
        }
    }

    private void checkTimeout(LoopState state) {
        if (state.timeoutExceeded(config.timeout())) {
            log.info("Timeout exceeded for run {}: {} > {}",
                    state.runId(), state.elapsed(), config.timeout());
            notifyLoopCompleted(state.runId(), state, TerminationReason.TIMEOUT);
            throw new AgentLoopTerminatedException(
                    TerminationReason.TIMEOUT,
                    "Timeout exceeded: " + config.timeout(),
                    state
            );
        }
    }

    private void checkCostLimit(LoopState state) {
        if (config.costLimit() > 0 && state.costExceeded(config.costLimit())) {
            log.info("Cost limit exceeded for run {}: ${} > ${}",
                    state.runId(),
                    String.format("%.4f", state.estimatedCost()),
                    String.format("%.4f", config.costLimit()));
            notifyLoopCompleted(state.runId(), state, TerminationReason.COST_LIMIT_EXCEEDED);
            throw new AgentLoopTerminatedException(
                    TerminationReason.COST_LIMIT_EXCEEDED,
                    String.format("Cost $%.4f exceeds limit $%.4f",
                            state.estimatedCost(), config.costLimit()),
                    state
            );
        }
    }

    private void checkMaxTurns(LoopState state) {
        if (state.maxTurnsReached(config.maxTurns())) {
            log.info("Max turns reached for run {}: {}/{}",
                    state.runId(), state.currentTurn(), config.maxTurns());
            notifyLoopCompleted(state.runId(), state, TerminationReason.MAX_TURNS_REACHED);
            throw new AgentLoopTerminatedException(
                    TerminationReason.MAX_TURNS_REACHED,
                    "Max turns reached: " + config.maxTurns(),
                    state
            );
        }
    }

    private void checkStuckDetection(LoopState state, ChatClientResponse response) {
        if (config.stuckThreshold() > 0 && state.isStuck(config.stuckThreshold())) {
            log.info("Agent stuck for run {}: same output {} times",
                    state.runId(), config.stuckThreshold());
            notifyLoopCompleted(state.runId(), state, TerminationReason.STUCK_DETECTED);
            throw new AgentLoopTerminatedException(
                    TerminationReason.STUCK_DETECTED,
                    "Agent stuck: same output " + config.stuckThreshold() + " times",
                    state,
                    response
            );
        }
    }

    private boolean shouldEvaluateJury(LoopState state) {
        if (jury == null || config.juryEvaluationInterval() <= 0) {
            return false;
        }
        // Evaluate at configured intervals (1-indexed for human readability)
        return (state.currentTurn()) % config.juryEvaluationInterval() == 0;
    }

    // --- Public API ---

    /**
     * Signals the loop to abort at the next safe point.
     * <p>
     * The abort will be checked before the next LLM call, not during.
     * This allows any in-progress operation to complete gracefully.
     */
    public void abort() {
        abortSignal.set(true);
        log.debug("Abort signal set");
    }

    /**
     * Checks if an abort has been signalled.
     */
    public boolean isAbortSignalled() {
        return abortSignal.get();
    }

    /**
     * Gets the current loop state (for the current thread).
     * <p>
     * Useful for debugging and status displays.
     */
    public LoopState getCurrentState() {
        return loopState.get();
    }

    /**
     * Gets the configuration.
     */
    public AgentLoopConfig getConfig() {
        return config;
    }

    // --- Helper methods ---

    private String extractUserMessage(ChatClientRequest request) {
        // Extract user message from request for logging/listeners
        if (request == null || request.prompt() == null) {
            return "";
        }
        var messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        // Get the last user message
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
                return msg.getText();
            }
        }
        return "";
    }

    private long extractTokensUsed(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return 0;
        }
        var metadata = response.chatResponse().getMetadata();
        if (metadata == null) {
            return 0;
        }
        var usage = metadata.getUsage();
        if (usage == null) {
            return 0;
        }
        return usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;
    }

    private double estimateCost(long tokens) {
        // Simple cost estimate based on Claude 3.5 Sonnet pricing
        // ~$3/1M input, $15/1M output, average ~$6/1M
        return tokens * 0.000006;
    }

    private int computeOutputSignature(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return 0;
        }
        var result = response.chatResponse().getResult();
        if (result == null || result.getOutput() == null) {
            return 0;
        }
        String text = result.getOutput().getText();
        return text != null ? text.hashCode() : 0;
    }

    private boolean hasToolCalls(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return false;
        }
        // Use ChatResponse.hasToolCalls() which checks ALL generations,
        // not just the first one. Anthropic returns TEXT and TOOL_USE as
        // separate content blocks, creating multiple generations.
        return response.chatResponse().hasToolCalls();
    }

    // --- Listener notifications ---

    private void notifyLoopStarted(String runId, String message) {
        for (var listener : listeners) {
            try {
                listener.onLoopStarted(runId, message);
            } catch (Exception e) {
                log.warn("Listener error in onLoopStarted: {}", e.getMessage());
            }
        }
    }

    private void notifyTurnStarted(String runId, int turn) {
        for (var listener : listeners) {
            try {
                listener.onTurnStarted(runId, turn);
            } catch (Exception e) {
                log.warn("Listener error in onTurnStarted: {}", e.getMessage());
            }
        }
    }

    private void notifyTurnCompleted(String runId, int turn, TerminationReason reason) {
        for (var listener : listeners) {
            try {
                listener.onTurnCompleted(runId, turn, reason);
            } catch (Exception e) {
                log.warn("Listener error in onTurnCompleted: {}", e.getMessage());
            }
        }
    }

    private void notifyLoopCompleted(String runId, LoopState state, TerminationReason reason) {
        for (var listener : listeners) {
            try {
                listener.onLoopCompleted(runId, state, reason);
            } catch (Exception e) {
                log.warn("Listener error in onLoopCompleted: {}", e.getMessage());
            }
        }
    }

    private void notifyLoopFailed(String runId, LoopState state, Throwable error) {
        for (var listener : listeners) {
            try {
                listener.onLoopFailed(runId, state, error);
            } catch (Exception e) {
                log.warn("Listener error in onLoopFailed: {}", e.getMessage());
            }
        }
    }

    // --- Builder ---

    /**
     * Creates a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link AgentLoopAdvisor}.
     */
    public static class Builder extends ToolCallAdvisor.Builder<Builder> {

        private ToolCallingManager toolCallingManager;
        private int advisorOrder = 0;
        private int maxTurns = AgentLoopConfig.DEFAULT_MAX_TURNS;
        private Duration timeout = AgentLoopConfig.DEFAULT_TIMEOUT;
        private double costLimit = AgentLoopConfig.DEFAULT_COST_LIMIT;
        private int stuckThreshold = AgentLoopConfig.DEFAULT_STUCK_THRESHOLD;
        private int juryEvaluationInterval = 0;
        private Jury jury = null;
        private Path workingDirectory = Path.of(".");
        private List<AgentLoopListener> listeners = new ArrayList<>();

        protected Builder() {
        }

        @Override
        public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return super.toolCallingManager(toolCallingManager);
        }

        @Override
        public Builder advisorOrder(int order) {
            this.advisorOrder = order;
            return super.advisorOrder(order);
        }

        /**
         * Sets the maximum number of turns allowed.
         *
         * @param maxTurns maximum turns, must be at least 1
         * @return this builder
         */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /**
         * Sets the timeout duration.
         *
         * @param timeout maximum duration for the loop
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the cost limit in dollars.
         *
         * @param costLimit maximum cost, 0 to disable
         * @return this builder
         */
        public Builder costLimit(double costLimit) {
            this.costLimit = costLimit;
            return this;
        }

        /**
         * Sets the stuck detection threshold.
         *
         * @param threshold consecutive identical outputs to detect stuck, 0 to disable
         * @return this builder
         */
        public Builder stuckThreshold(int threshold) {
            this.stuckThreshold = threshold;
            return this;
        }

        /**
         * Configures jury evaluation.
         *
         * @param jury the jury instance
         * @param evaluationInterval evaluate every N turns, 0 to disable
         * @return this builder
         */
        public Builder jury(Jury jury, int evaluationInterval) {
            this.jury = jury;
            this.juryEvaluationInterval = evaluationInterval;
            return this;
        }

        /**
         * Sets the working directory for jury evaluation.
         *
         * @param workingDirectory the working directory
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Adds a listener for loop events.
         *
         * @param listener the listener to add
         * @return this builder
         */
        public Builder listener(AgentLoopListener listener) {
            this.listeners.add(listener);
            return this;
        }

        /**
         * Applies a preset configuration.
         *
         * @param config the configuration to apply
         * @return this builder
         */
        public Builder config(AgentLoopConfig config) {
            this.maxTurns = config.maxTurns();
            this.timeout = config.timeout();
            this.costLimit = config.costLimit();
            this.stuckThreshold = config.stuckThreshold();
            this.juryEvaluationInterval = config.juryEvaluationInterval();
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public AgentLoopAdvisor build() {
            return new AgentLoopAdvisor(this);
        }
    }
}
