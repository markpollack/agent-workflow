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
package io.github.markpollack.workflow.patterns.statemachine;

import io.github.markpollack.workflow.core.LoopPattern;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.workflow.core.LoopStatus;
import io.github.markpollack.workflow.core.TerminationReason;
import io.github.markpollack.workflow.patterns.judge.ScoreText;
import io.github.markpollack.workflow.patterns.judge.SpringAiJuryAdapter;
import io.github.markpollack.workflow.patterns.statemachine.StateMachineConfig.StateHandler;
import io.github.markpollack.workflow.patterns.statemachine.StateMachineConfig.StateContext;
import io.github.markpollack.workflow.patterns.statemachine.StateMachineConfig.TransitionResult;
import io.github.markpollack.workflow.strategy.TerminationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.judge.verdict.Verdict;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Status-Based State Machine loop pattern.
 * <p>
 * This pattern implements explicit state transitions with defined terminal states.
 * Used by OpenHands, Embabel, and AgentLite.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Explicit state definitions (INITIAL, RUNNING, COMPLETED, etc.)</li>
 *   <li>Valid transition guards</li>
 *   <li>State-specific handlers</li>
 *   <li>Terminal states end the loop</li>
 *   <li>Tool calling via Spring AI ChatClient</li>
 *   <li>Full W&amp;B-lite observability</li>
 * </ul>
 *
 * <p>Typical state flow:
 * <pre>
 * INITIAL → RUNNING → AWAITING_JUDGMENT → COMPLETED
 *              ↓                ↓
 *           FAILED          FAILED
 * </pre>
 *
 * <p>Uses Spring AI ChatClient directly - synchronous API, no Reactor.
 */
public class StateMachineLoop implements LoopPattern<StateMachineResult> {

    private static final Logger log = LoggerFactory.getLogger(StateMachineLoop.class);

    private final StateMachineConfig config;
    private final List<LoopListener> listeners;
    private final AtomicBoolean abortSignal;
    private final SpringAiJuryAdapter juryAdapter;

    private StateMachineLoop(Builder builder) {
        this.config = builder.config;
        this.listeners = new CopyOnWriteArrayList<>(builder.listeners);
        this.abortSignal = new AtomicBoolean(false);

        // Create jury adapter if jury configured
        if (config.jury().isPresent()) {
            this.juryAdapter = new SpringAiJuryAdapter(
                    config.jury().get(),
                    "state-machine-jury"
            );
        } else {
            this.juryAdapter = null;
        }
    }

    /**
     * Record of a state transition.
     */
    public record StateTransition(
            int iteration,
            AgentState fromState,
            AgentState toState,
            String output,
            Duration duration,
            String reason
    ) {}

    /**
     * Loop event listener.
     */
    public interface LoopListener {
        default void onLoopStarted(String runId, String userMessage) {}
        default void onStateEntered(String runId, AgentState state, int iteration) {}
        default void onStateExited(String runId, AgentState state, String output) {}
        default void onTransition(String runId, AgentState from, AgentState to, String reason) {}
        default void onLoopCompleted(String runId, AgentState finalState, TerminationReason reason) {}
        default void onLoopFailed(String runId, Throwable error) {}
    }

    @Override
    public StateMachineResult execute(
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools
    ) {
        String runId = UUID.randomUUID().toString();
        abortSignal.set(false);

        // Initialize state
        LoopState loopState = LoopState.initial(runId);

        // Get initial state
        AgentState currentState = config.getState(config.initialState());
        if (currentState == null) {
            currentState = AgentState.INITIAL;
        }

        notifyLoopStarted(runId, userMessage);
        log.debug("State machine loop started: runId={}, initialState={}",
                runId, currentState.name());

        try {
            StateLoopResult result = executeStateLoop(loopState, userMessage, chatClient, tools, runId, currentState);

            notifyLoopCompleted(runId, result.finalState, result.reason);
            log.info("State machine loop completed: finalState={}, totalTransitions={}, reason={}",
                    result.finalState.name(), result.transitions.size(), result.reason.name());

            LoopStatus status = result.finalState.terminal() && "COMPLETED".equals(result.finalState.name())
                    ? LoopStatus.COMPLETED : LoopStatus.STOPPED;

            return StateMachineResult.terminated(
                    runId,
                    result.lastOutput,
                    result.reason,
                    status,
                    result.transitions.size(),
                    result.state.elapsed(),
                    result.state.totalTokensUsed(),
                    result.state.estimatedCost(),
                    result.transitions,
                    result.finalState,
                    result.attributes
            );

        } catch (Exception error) {
            log.error("Loop failed for run {}: {}", runId,
                    error.getMessage() != null ? error.getMessage() : "Unknown", error);
            notifyLoopFailed(runId, error);

            return StateMachineResult.failed(
                    runId,
                    loopState.currentTurn(),
                    loopState.elapsed(),
                    List.of(),
                    AgentState.FAILED
            );
        }
    }

    /**
     * Main state machine loop execution.
     */
    private StateLoopResult executeStateLoop(
            LoopState loopState,
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools,
            String runId,
            AgentState initialState
    ) {
        List<StateTransition> transitions = new ArrayList<>();
        Map<String, Object> attributes = new HashMap<>();
        String lastOutput = null;
        LoopState currentLoopState = loopState;
        AgentState currentState = initialState;
        TerminationReason terminationReason = TerminationReason.NOT_TERMINATED;

        for (int iteration = 1; iteration <= config.maxIterations() && !abortSignal.get(); iteration++) {
            Instant iterStart = Instant.now();

            // Check if terminal state
            if (currentState.terminal()) {
                terminationReason = TerminationReason.STATE_TERMINAL;
                break;
            }

            // Check timeout
            if (currentLoopState.timeoutExceeded(config.timeout())) {
                terminationReason = TerminationReason.TIMEOUT;
                currentState = AgentState.FAILED;
                break;
            }

            notifyStateEntered(loopState.runId(), currentState, iteration);

            // Execute state handler or default behavior
            TransitionResult transitionResult;
            StateHandler handler = config.stateHandlers().get(currentState.name());

            if (handler != null) {
                // Use custom handler
                StateContext ctx = new StateContext(currentState, userMessage, lastOutput, iteration, attributes);
                transitionResult = handler.handle(ctx);
            } else {
                // Default behavior: use ChatClient
                transitionResult = executeDefaultBehavior(
                        userMessage,
                        currentLoopState,
                        currentState,
                        chatClient,
                        tools,
                        lastOutput
                );
            }

            lastOutput = (String) transitionResult.output();
            notifyStateExited(loopState.runId(), currentState, lastOutput);

            // Update loop state with turn metrics
            long tokensUsed = 0; // Would need to track from ChatResponse
            double cost = 0.0;
            currentLoopState = currentLoopState.completeTurn(
                    tokensUsed,
                    cost,
                    false,
                    lastOutput != null ? lastOutput.hashCode() : 0
            );

            // Check for jury evaluation
            if (config.evaluateOnStates().contains(currentState.name()) && config.jury().isPresent() && juryAdapter != null) {
                log.debug("Iteration {} jury evaluation started", iteration);

                Verdict verdict = juryAdapter.evaluate(
                        currentLoopState,
                        null,
                        config.workingDirectory()
                );

                if (verdict != null) {
                    boolean passed = verdict.judgment().pass();
                    OptionalDouble score = verdict.judgment().effectiveScore();

                    log.debug("Iteration {} jury evaluation completed: status={}, score={}",
                            iteration, verdict.judgment().status(), ScoreText.describe(score));

                    if (passed) {
                        // A passing jury that measured nothing still passes; the reported score
                        // is its derived view, and the reason names it as such rather than as 0.0.
                        transitionResult = TransitionResult.complete(lastOutput,
                                "Jury passed with score " + ScoreText.describe(score));
                    }
                }
            }

            // Determine next state
            AgentState previousState = currentState;
            if (transitionResult.nextState() != null) {
                AgentState nextState = config.getState(transitionResult.nextState());
                if (nextState == null) {
                    // Try predefined states
                    nextState = switch (transitionResult.nextState()) {
                        case "COMPLETED" -> AgentState.COMPLETED;
                        case "FAILED" -> AgentState.FAILED;
                        case "CANCELLED" -> AgentState.CANCELLED;
                        case "RUNNING" -> AgentState.RUNNING;
                        case "INITIAL" -> AgentState.INITIAL;
                        case "AWAITING_JUDGMENT" -> AgentState.AWAITING_JUDGMENT;
                        default -> null;
                    };
                }

                if (nextState != null && currentState.canTransitionTo(nextState)) {
                    currentState = nextState;
                    notifyTransition(loopState.runId(), previousState, currentState, transitionResult.reason());
                    log.debug("State transition: {} -> {} (reason: {})",
                            previousState.name(), currentState.name(),
                            transitionResult.reason() != null ? transitionResult.reason() : "");
                } else if (nextState != null) {
                    log.warn("Invalid transition from {} to {}", currentState.name(), nextState.name());
                }
            }

            // Record transition
            Duration iterDuration = Duration.between(iterStart, Instant.now());
            transitions.add(new StateTransition(
                    iteration,
                    previousState,
                    currentState,
                    lastOutput,
                    iterDuration,
                    transitionResult.reason()
            ));

            log.debug("Iteration {} completed: state={}, duration={}ms",
                    iteration, currentState.name(), iterDuration.toMillis());

            // Check if should continue
            if (!transitionResult.shouldContinue()) {
                terminationReason = TerminationReason.STATE_TERMINAL;
                break;
            }
        }

        // Handle max iterations
        if (terminationReason == TerminationReason.NOT_TERMINATED) {
            terminationReason = TerminationReason.MAX_ITERATIONS_REACHED;
        }

        return new StateLoopResult(currentLoopState, transitions, currentState, lastOutput, attributes, terminationReason);
    }

    /**
     * Default behavior when no state handler is defined.
     * Uses ChatClient directly for generation.
     */
    private TransitionResult executeDefaultBehavior(
            String userMessage,
            LoopState state,
            AgentState currentState,
            ChatClient chatClient,
            List<ToolCallback> tools,
            String lastOutput
    ) {
        // INITIAL state: transition to RUNNING
        if ("INITIAL".equals(currentState.name())) {
            return TransitionResult.transitionTo("RUNNING", null);
        }

        // RUNNING state: use ChatClient
        if ("RUNNING".equals(currentState.name())) {
            try {
                ChatResponse response = chatClient.prompt()
                        .user(userMessage)
                        .tools((Object[]) tools.toArray(new ToolCallback[0]))
                        .call()
                        .chatResponse();

                String output = extractOutputText(response);

                // Check for finish tool
                if (hasFinishTool(response, config.finishToolName())) {
                    return TransitionResult.complete(output, "Finish tool called");
                }

                // Transition to awaiting judgment if jury configured
                if (config.jury().isPresent()) {
                    return TransitionResult.transitionTo("AWAITING_JUDGMENT", output);
                }

                return TransitionResult.transitionTo("COMPLETED", output);

            } catch (Exception e) {
                log.error("ChatClient call failed", e);
                return TransitionResult.fail(null, "Generation failed: " + e.getMessage());
            }
        }

        // AWAITING_JUDGMENT: jury decides (handled in main loop)
        if ("AWAITING_JUDGMENT".equals(currentState.name())) {
            return TransitionResult.stay(lastOutput);
        }

        // Default: stay in current state
        return TransitionResult.stay(lastOutput);
    }

    // --- Helper methods for ChatResponse processing ---

    private String extractOutputText(ChatResponse response) {
        if (response == null || response.getResult() == null) return "";
        var output = response.getResult().getOutput();
        return output != null && output.getText() != null ? output.getText() : "";
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

    @Override
    public TerminationStrategy terminationStrategy() {
        return TerminationStrategy.allOf(List.of(
                TerminationStrategy.maxTurns(100),
                TerminationStrategy.timeout(Duration.ofMinutes(60)),
                TerminationStrategy.abortSignal()
        ));
    }

    @Override
    public LoopType loopType() {
        return LoopType.STATUS_BASED_STATE_MACHINE;
    }

    /**
     * Signals the loop to abort.
     */
    public void abort() {
        abortSignal.set(true);
    }

    /**
     * Adds a loop listener.
     */
    public void addListener(LoopListener listener) {
        listeners.add(listener);
    }

    // --- Inner classes ---

    /**
     * Internal result of state loop execution.
     */
    private record StateLoopResult(
            LoopState state,
            List<StateTransition> transitions,
            AgentState finalState,
            String lastOutput,
            Map<String, Object> attributes,
            TerminationReason reason
    ) {}

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

    private void notifyStateEntered(String runId, AgentState state, int iteration) {
        for (var listener : listeners) {
            try {
                listener.onStateEntered(runId, state, iteration);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyStateExited(String runId, AgentState state, String output) {
        for (var listener : listeners) {
            try {
                listener.onStateExited(runId, state, output);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyTransition(String runId, AgentState from, AgentState to, String reason) {
        for (var listener : listeners) {
            try {
                listener.onTransition(runId, from, to, reason);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyLoopCompleted(String runId, AgentState finalState, TerminationReason reason) {
        for (var listener : listeners) {
            try {
                listener.onLoopCompleted(runId, finalState, reason);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    private void notifyLoopFailed(String runId, Throwable error) {
        for (var listener : listeners) {
            try {
                listener.onLoopFailed(runId, error);
            } catch (Exception e) {
                log.warn("Listener error", e);
            }
        }
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private StateMachineConfig config;
        private List<LoopListener> listeners = new ArrayList<>();

        public Builder config(StateMachineConfig config) {
            this.config = config;
            return this;
        }

        public Builder addListener(LoopListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public StateMachineLoop build() {
            if (config == null) {
                throw new IllegalStateException("Config must be set");
            }
            return new StateMachineLoop(this);
        }
    }
}
