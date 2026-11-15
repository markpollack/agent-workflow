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
package io.github.markpollack.workflow.patterns.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.github.markpollack.workflow.patterns.ToolCallListener;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer ObservationHandler that bridges Spring AI's tool calling observations
 * to our ToolCallListener interface.
 * <p>
 * Spring AI's DefaultToolCallingManager wraps each tool execution in a Micrometer
 * observation, providing callbacks for start, stop, and error events. This handler
 * captures those events and forwards them to registered ToolCallListeners.
 * <p>
 * Usage:
 * <pre>
 * ObservationRegistry registry = ObservationRegistry.create();
 * registry.observationConfig()
 *     .observationHandler(new ToolCallObservationHandler(listeners));
 *
 * ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
 *     .observationRegistry(registry)
 *     .build();
 * </pre>
 */
public class ToolCallObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

    private final List<ToolCallListener> listeners;

    // Track start times for duration calculation
    private final Map<Observation.Context, Instant> startTimes = new ConcurrentHashMap<>();

    // Context for run/turn info - can be set by the loop before tool execution
    private volatile String currentRunId = "unknown";
    private volatile int currentTurn = 0;

    public ToolCallObservationHandler(List<ToolCallListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    /**
     * Sets the current run context. Should be called by the loop before each turn.
     */
    public void setContext(String runId, int turn) {
        this.currentRunId = runId;
        this.currentTurn = turn;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(ToolCallingObservationContext context) {
        startTimes.put(context, Instant.now());

        var toolCall = createToolCallInfo(context);

        for (var listener : listeners) {
            try {
                listener.onToolExecutionStarted(
                        currentRunId,
                        currentTurn,
                        toolCall
                );
            } catch (Exception e) {
                // Don't let listener errors affect tool execution
            }
        }
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        Instant startTime = startTimes.remove(context);
        Duration duration = startTime != null
                ? Duration.between(startTime, Instant.now())
                : Duration.ZERO;

        var toolCall = createToolCallInfo(context);
        String result = context.getToolCallResult();

        for (var listener : listeners) {
            try {
                listener.onToolExecutionCompleted(
                        currentRunId,
                        currentTurn,
                        toolCall,
                        result,
                        duration
                );
            } catch (Exception e) {
                // Don't let listener errors affect tool execution
            }
        }
    }

    @Override
    public void onError(ToolCallingObservationContext context) {
        Instant startTime = startTimes.remove(context);
        Duration duration = startTime != null
                ? Duration.between(startTime, Instant.now())
                : Duration.ZERO;

        var toolCall = createToolCallInfo(context);
        Throwable error = context.getError();

        for (var listener : listeners) {
            try {
                listener.onToolExecutionFailed(
                        currentRunId,
                        currentTurn,
                        toolCall,
                        error,
                        duration
                );
            } catch (Exception e) {
                // Don't let listener errors affect tool execution
            }
        }
    }

    /**
     * Creates a synthetic ToolCall info object from the observation context.
     */
    private org.springframework.ai.chat.messages.AssistantMessage.ToolCall createToolCallInfo(
            ToolCallingObservationContext context) {
        return new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                "observation-" + System.identityHashCode(context),
                "function",
                context.getToolDefinition().name(),
                context.getToolCallArguments()
        );
    }

    /**
     * Creates a handler with a single listener.
     */
    public static ToolCallObservationHandler of(ToolCallListener listener) {
        return new ToolCallObservationHandler(List.of(listener));
    }

    /**
     * Creates a handler with multiple listeners.
     */
    public static ToolCallObservationHandler of(List<ToolCallListener> listeners) {
        return new ToolCallObservationHandler(listeners);
    }
}
