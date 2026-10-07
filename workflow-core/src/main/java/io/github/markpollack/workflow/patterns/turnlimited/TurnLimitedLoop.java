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
package io.github.markpollack.workflow.patterns.turnlimited;

import io.github.markpollack.workflow.core.LoopPattern;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.workflow.core.TerminationReason;
import io.github.markpollack.workflow.core.ToolCallListener;
import io.github.markpollack.workflow.patterns.judge.ScoreText;
import io.github.markpollack.workflow.patterns.judge.SpringAiJuryAdapter;
import io.github.markpollack.workflow.strategy.TerminationStrategy;
import io.github.markpollack.workflow.strategy.TerminationStrategy.TerminationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.judge.verdict.Verdict;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turn-Limited Multi-Condition Loop Pattern.
 * <p>
 * This is the primary pattern used by Claude CLI, Gemini CLI, Swarm, and SWE-Agent.
 * Uses Spring AI ChatClient directly - no adapters or Reactor needed.
 * <p>
 * Each "turn" consists of:
 * <ol>
 *   <li>Send messages to ChatClient with available tools</li>
 *   <li>Check termination conditions</li>
 *   <li>Execute tool calls if present (via ChatClient's tool calling)</li>
 *   <li>Add tool results to conversation</li>
 *   <li>Repeat</li>
 * </ol>
 */
public class TurnLimitedLoop implements LoopPattern<TurnLimitedResult> {

    private static final Logger log = LoggerFactory.getLogger(TurnLimitedLoop.class);

    private final TurnLimitedConfig config;
    private final List<LoopListener> listeners;
    private final List<ToolCallListener> toolCallListeners;
    private final AtomicBoolean abortSignal;
    private final SpringAiJuryAdapter juryAdapter;

    private TurnLimitedLoop(Builder builder) {
        this.config = builder.config;
        this.listeners = new ArrayList<>(builder.listeners);
        this.toolCallListeners = new ArrayList<>(builder.toolCallListeners);
        this.abortSignal = new AtomicBoolean(false);

        // Create jury adapter if jury configured
        if (config.jury().isPresent()) {
            this.juryAdapter = new SpringAiJuryAdapter(
                    config.jury().get(),
                    "turn-limited-jury"
            );
        } else {
            this.juryAdapter = null;
        }
    }

    /**
     * Signals the loop to abort at the next safe point.
     */
    public void abort() {
        abortSignal.set(true);
    }

    @Override
    public TurnLimitedResult execute(
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools
    ) {
        String runId = UUID.randomUUID().toString();
        abortSignal.set(false);
        long startTime = System.currentTimeMillis();

        // Initialize loop state
        LoopState state = LoopState.initial(runId);

        log.debug("Loop started: runId={}, maxTurns={}, timeout={}ms",
                runId, config.maxTurns(), config.timeout().toMillis());

        notifyLoopStarted(runId, userMessage);

        try {
            // Execute the turn loop
            LoopExecutionResult result = executeLoop(state, userMessage, chatClient, tools);

            long durationMs = System.currentTimeMillis() - startTime;

            log.info("Loop completed: {} turns, reason={}, tokens={}, duration={}ms",
                    result.state.currentTurn(),
                    result.reason.name(),
                    result.state.totalTokensUsed(),
                    durationMs);

            // Extract output from last response
            String output = extractOutputText(result.lastResponse);

            TurnLimitedResult loopResult = TurnLimitedResult.terminated(
                    runId,
                    output,
                    result.reason,
                    result.state,
                    result.lastVerdict
            );

            notifyLoopCompleted(loopResult);
            return loopResult;

        } catch (Exception error) {
            log.error("Loop failed for run {}: {}", runId,
                    error.getMessage() != null ? error.getMessage() : "Unknown", error);

            TurnLimitedResult result = TurnLimitedResult.failed(runId, state);
            notifyLoopFailed(result, error);
            return result;
        }
    }

    /**
     * Main loop execution - iterates until termination condition met.
     */
    private LoopExecutionResult executeLoop(
            LoopState state,
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools
    ) {
        LoopState currentState = state;
        Verdict lastVerdict = null;
        ChatResponse lastResponse = null;

        while (true) {
            TurnResult turnResult = executeTurn(currentState, userMessage, chatClient, tools);

            currentState = turnResult.state;
            lastResponse = turnResult.response;

            if (turnResult.verdict != null) {
                lastVerdict = turnResult.verdict;
            }

            if (turnResult.terminated) {
                return new LoopExecutionResult(currentState, turnResult.reason, lastVerdict, lastResponse);
            }
        }
    }

    /**
     * Execute a single turn of the loop.
     */
    private TurnResult executeTurn(
            LoopState state,
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools
    ) {
        int turn = state.currentTurn();

        // Check pre-turn termination conditions
        TerminationResult preCheck = checkPreTurnTermination(state);
        if (preCheck.shouldTerminate()) {
            return TurnResult.terminated(state, preCheck.reason(), null, null);
        }

        notifyTurnStarted(state.runId(), turn);
        int turnDisplay = turn + 1;  // 1-indexed for human readability
        log.info("Turn {} starting", turnDisplay);

        try {
            // Use ChatClient directly with tools
            ChatResponse response = chatClient.prompt()
                    .user(userMessage)
                    .toolCallbacks(tools.toArray(new ToolCallback[0]))
                    .call()
                    .chatResponse();

            // Log tool calls from response for visibility
            logToolCalls(turnDisplay, response);

            long tokensUsed = extractTokensUsed(response);
            double cost = estimateCost(tokensUsed);

            log.debug("Turn {} metrics: tokens={}, cost={}", turnDisplay, tokensUsed, cost);

            // Check if the response has tool calls
            boolean hasToolCalls = hasToolCalls(response);

            if (!hasToolCalls) {
                // No tool calls = conversation complete
                log.info("Turn {} completed: no tool calls (conversation finished)", turnDisplay);

                LoopState newState = state.completeTurn(tokensUsed, cost, false, outputSignature(response));
                notifyTurnCompleted(state.runId(), turn, TerminationReason.FINISH_TOOL_CALLED);

                return TurnResult.terminated(newState, TerminationReason.FINISH_TOOL_CALLED, null, response);
            }

            // Check for finish tool
            if (hasFinishTool(response, config.finishToolName())) {
                log.info("Turn {} completed: finish tool '{}' called", turnDisplay, config.finishToolName());

                LoopState newState = state.completeTurn(tokensUsed, cost, true, outputSignature(response));
                notifyTurnCompleted(state.runId(), turn, TerminationReason.FINISH_TOOL_CALLED);

                return TurnResult.terminated(newState, TerminationReason.FINISH_TOOL_CALLED, null, response);
            }

            // Tool calls handled by ChatClient's internal tool calling
            log.info("Turn {} completed: tools executed, continuing", turnDisplay);

            // Update state
            LoopState newState = state.completeTurn(tokensUsed, cost, true, outputSignature(response));

            // Check post-turn termination (jury evaluation, etc.)
            PostTurnCheck postCheck = checkPostTurnTermination(newState, turn, response);

            notifyTurnCompleted(state.runId(), turn,
                    postCheck.shouldTerminate ? postCheck.reason : null);

            if (postCheck.shouldTerminate) {
                return TurnResult.terminated(newState, postCheck.reason, postCheck.verdict, response);
            }

            return TurnResult.continuing(newState, postCheck.verdict, response);

        } catch (Exception error) {
            log.error("Turn {} failed: {}", turn,
                    error.getMessage() != null ? error.getMessage() : "Unknown");

            // Re-throw to be handled by execute()
            throw new RuntimeException("Turn execution failed", error);
        }
    }

    /**
     * Check termination conditions before executing a turn.
     */
    private TerminationResult checkPreTurnTermination(LoopState state) {
        // Abort signal
        if (abortSignal.get() || state.abortSignalled()) {
            return TerminationResult.terminate(TerminationReason.EXTERNAL_SIGNAL, "Abort signal received");
        }

        // Max turns
        if (state.maxTurnsReached(config.maxTurns())) {
            return TerminationResult.terminate(TerminationReason.MAX_TURNS_REACHED,
                    "Reached max turns: " + config.maxTurns());
        }

        // Timeout
        if (state.timeoutExceeded(config.timeout())) {
            return TerminationResult.terminate(TerminationReason.TIMEOUT,
                    "Timeout exceeded: " + config.timeout());
        }

        // Cost limit
        if (state.costExceeded(config.costLimit())) {
            return TerminationResult.terminate(TerminationReason.COST_LIMIT_EXCEEDED,
                    String.format("Cost $%.4f > limit $%.4f", state.estimatedCost(), config.costLimit()));
        }

        // Stuck detection
        if (state.isStuck(config.stuckThreshold())) {
            return TerminationResult.terminate(TerminationReason.STUCK_DETECTED,
                    "Agent stuck: same output " + config.stuckThreshold() + " times");
        }

        return TerminationResult.continueLoop();
    }

    /**
     * Check termination conditions after executing a turn (includes jury evaluation).
     */
    private PostTurnCheck checkPostTurnTermination(
            LoopState state,
            int turn,
            ChatResponse response
    ) {
        // Check if we should evaluate with jury this turn
        if (config.jury().isEmpty()) {
            return PostTurnCheck.continueLoop();
        }

        boolean shouldEvaluate = config.evaluateEveryNTurns() > 0 &&
                turn % config.evaluateEveryNTurns() == 0;

        if (!shouldEvaluate) {
            return PostTurnCheck.continueLoop();
        }

        // Evaluate with jury (synchronous call)
        Verdict verdict = juryAdapter.evaluate(state, response, config.workingDirectory());
        OptionalDouble score = verdict.judgment().effectiveScore();

        if (verdict.judgment().pass()) {
            return PostTurnCheck.terminate(
                    TerminationReason.SCORE_THRESHOLD_MET,
                    verdict,
                    String.format("Jury passed with score %s", ScoreText.describe(score))
            );
        }

        // An aggregate that made no measurement has no score to compare against the threshold,
        // so the loop takes another turn rather than terminating on a value it never received.
        if (config.scoreThreshold() > 0 && score.isPresent() && score.getAsDouble() >= config.scoreThreshold()) {
            return PostTurnCheck.terminate(
                    TerminationReason.SCORE_THRESHOLD_MET,
                    verdict,
                    String.format("Score %.2f >= threshold %.2f", score.getAsDouble(), config.scoreThreshold())
            );
        }

        return PostTurnCheck.continueLoop(verdict);
    }

    // --- Helper methods for ChatResponse processing ---

    private String extractOutputText(ChatResponse response) {
        if (response == null || response.getResult() == null) return null;
        var output = response.getResult().getOutput();
        return output != null ? output.getText() : null;
    }

    private boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getResult() == null) return false;
        var output = response.getResult().getOutput();
        return output != null && output.hasToolCalls();
    }

    private boolean hasFinishTool(ChatResponse response, String finishToolName) {
        if (!hasToolCalls(response)) return false;
        var toolCalls = response.getResult().getOutput().getToolCalls();
        return toolCalls.stream().anyMatch(tc -> tc.name().equals(finishToolName));
    }

    private void logToolCalls(int turn, ChatResponse response) {
        if (!hasToolCalls(response)) return;
        var toolCalls = response.getResult().getOutput().getToolCalls();
        for (var tc : toolCalls) {
            String args = tc.arguments() != null ? truncate(tc.arguments(), 100) : "";
            log.info("  Turn {} tool call: {}({})", turn, tc.name(), args);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private long extractTokensUsed(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return 0;
        var usage = response.getMetadata().getUsage();
        if (usage == null) return 0;
        return usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;
    }

    private double estimateCost(long tokens) {
        // Simple cost estimate - can be made configurable
        // Using Claude 3.5 Sonnet pricing as reference: ~$3/1M input, $15/1M output
        return tokens * 0.000006; // Average ~$6/1M tokens
    }

    private int outputSignature(ChatResponse response) {
        if (response == null || response.getResult() == null) return 0;
        var content = response.getResult().getOutput().getText();
        return content != null ? content.hashCode() : 0;
    }

    @Override
    public TerminationStrategy terminationStrategy() {
        return TerminationStrategy.allOf(List.of(
                TerminationStrategy.maxTurns(100),
                TerminationStrategy.timeout(Duration.ofMinutes(30)),
                TerminationStrategy.stuckDetection(3),
                TerminationStrategy.abortSignal()
        ));
    }

    @Override
    public LoopType loopType() {
        return LoopType.TURN_LIMITED_MULTI_CONDITION;
    }

    // --- Listener notifications ---

    private void notifyLoopStarted(String runId, String userMessage) {
        for (var listener : listeners) {
            try {
                listener.onLoopStarted(runId, userMessage);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyTurnStarted(String runId, int turn) {
        for (var listener : listeners) {
            try {
                listener.onTurnStarted(runId, turn);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyTurnCompleted(String runId, int turn, TerminationReason reason) {
        for (var listener : listeners) {
            try {
                listener.onTurnCompleted(runId, turn, reason);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyLoopCompleted(TurnLimitedResult result) {
        for (var listener : listeners) {
            try {
                listener.onLoopCompleted(result);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyLoopFailed(TurnLimitedResult result, Throwable error) {
        for (var listener : listeners) {
            try {
                listener.onLoopFailed(result, error);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    // --- Inner classes ---

    /**
     * Result of a single turn execution.
     */
    private record TurnResult(
            LoopState state,
            boolean terminated,
            TerminationReason reason,
            Verdict verdict,
            ChatResponse response
    ) {
        static TurnResult continuing(LoopState state, Verdict verdict, ChatResponse response) {
            return new TurnResult(state, false, null, verdict, response);
        }

        static TurnResult terminated(LoopState state, TerminationReason reason, Verdict verdict, ChatResponse response) {
            return new TurnResult(state, true, reason, verdict, response);
        }
    }

    /**
     * Result of loop execution.
     */
    private record LoopExecutionResult(
            LoopState state,
            TerminationReason reason,
            Verdict lastVerdict,
            ChatResponse lastResponse
    ) {}

    /**
     * Result of post-turn termination check.
     */
    private record PostTurnCheck(
            boolean shouldTerminate,
            TerminationReason reason,
            Verdict verdict,
            String message
    ) {
        static PostTurnCheck continueLoop() {
            return new PostTurnCheck(false, null, null, null);
        }

        static PostTurnCheck continueLoop(Verdict verdict) {
            return new PostTurnCheck(false, null, verdict, null);
        }

        static PostTurnCheck terminate(TerminationReason reason, Verdict verdict, String message) {
            return new PostTurnCheck(true, reason, verdict, message);
        }
    }

    /**
     * Listener for loop events.
     */
    public interface LoopListener {
        default void onLoopStarted(String runId, String userMessage) {}
        default void onTurnStarted(String runId, int turn) {}
        default void onTurnCompleted(String runId, int turn, TerminationReason reason) {}
        default void onLoopCompleted(TurnLimitedResult result) {}
        default void onLoopFailed(TurnLimitedResult result, Throwable error) {}
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TurnLimitedConfig config;
        private List<LoopListener> listeners = new ArrayList<>();
        private List<ToolCallListener> toolCallListeners = new ArrayList<>();

        public Builder config(TurnLimitedConfig config) {
            this.config = config;
            return this;
        }

        public Builder listener(LoopListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public Builder toolCallListener(ToolCallListener listener) {
            this.toolCallListeners.add(listener);
            return this;
        }

        public TurnLimitedLoop build() {
            if (config == null) {
                throw new IllegalStateException("Config must be set");
            }
            return new TurnLimitedLoop(this);
        }
    }
}
