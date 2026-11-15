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
import io.github.markpollack.workflow.patterns.ToolCallListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolCallObservationHandler")
class ToolCallObservationHandlerTest {

    @Mock
    private ToolCallListener listener;

    @Mock
    private ToolCallingObservationContext observationContext;

    @Mock
    private ToolDefinition toolDefinition;

    @Captor
    private ArgumentCaptor<AssistantMessage.ToolCall> toolCallCaptor;

    @Captor
    private ArgumentCaptor<Duration> durationCaptor;

    private ToolCallObservationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ToolCallObservationHandler(List.of(listener));
    }

    @Nested
    @DisplayName("supportsContext")
    class SupportsContext {

        @Test
        @DisplayName("should return true for ToolCallingObservationContext")
        void shouldReturnTrueForToolCallingContext() {
            assertThat(handler.supportsContext(observationContext)).isTrue();
        }

        @Test
        @DisplayName("should return false for other context types")
        void shouldReturnFalseForOtherContexts() {
            Observation.Context otherContext = new Observation.Context();
            assertThat(handler.supportsContext(otherContext)).isFalse();
        }
    }

    @Nested
    @DisplayName("onStart")
    class OnStart {

        @Test
        @DisplayName("should notify listener when tool execution starts")
        void shouldNotifyListenerOnStart() {
            when(toolDefinition.name()).thenReturn("test_tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{\"arg\": \"value\"}");

            handler.setContext("run-123", 2);
            handler.onStart(observationContext);

            verify(listener).onToolExecutionStarted(
                    eq("run-123"),
                    eq(2),
                    toolCallCaptor.capture()
            );

            AssistantMessage.ToolCall capturedToolCall = toolCallCaptor.getValue();
            assertThat(capturedToolCall.name()).isEqualTo("test_tool");
            assertThat(capturedToolCall.arguments()).isEqualTo("{\"arg\": \"value\"}");
            assertThat(capturedToolCall.type()).isEqualTo("function");
        }

        @Test
        @DisplayName("should use default context when not set")
        void shouldUseDefaultContextWhenNotSet() {
            when(toolDefinition.name()).thenReturn("some_tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");

            handler.onStart(observationContext);

            verify(listener).onToolExecutionStarted(
                    eq("unknown"),
                    eq(0),
                    any(AssistantMessage.ToolCall.class)
            );
        }
    }

    @Nested
    @DisplayName("onStop")
    class OnStop {

        @Test
        @DisplayName("should notify listener with result when tool execution completes")
        void shouldNotifyListenerWithResultOnStop() throws InterruptedException {
            when(toolDefinition.name()).thenReturn("test_tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");
            when(observationContext.getToolCallResult()).thenReturn("Tool result here");

            handler.setContext("run-456", 3);
            handler.onStart(observationContext);

            // Small delay to ensure measurable duration
            Thread.sleep(10);

            handler.onStop(observationContext);

            verify(listener).onToolExecutionCompleted(
                    eq("run-456"),
                    eq(3),
                    toolCallCaptor.capture(),
                    eq("Tool result here"),
                    durationCaptor.capture()
            );

            AssistantMessage.ToolCall capturedToolCall = toolCallCaptor.getValue();
            assertThat(capturedToolCall.name()).isEqualTo("test_tool");

            Duration capturedDuration = durationCaptor.getValue();
            assertThat(capturedDuration).isGreaterThan(Duration.ZERO);
        }

        @Test
        @DisplayName("should use zero duration if start time not tracked")
        void shouldUseZeroDurationIfNoStartTime() {
            when(toolDefinition.name()).thenReturn("test_tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");
            when(observationContext.getToolCallResult()).thenReturn("result");

            // Call onStop without calling onStart
            handler.onStop(observationContext);

            verify(listener).onToolExecutionCompleted(
                    anyString(),
                    anyInt(),
                    any(AssistantMessage.ToolCall.class),
                    eq("result"),
                    eq(Duration.ZERO)
            );
        }
    }

    @Nested
    @DisplayName("onError")
    class OnError {

        @Test
        @DisplayName("should notify listener when tool execution fails")
        void shouldNotifyListenerOnError() {
            RuntimeException testError = new RuntimeException("Tool failed");

            when(toolDefinition.name()).thenReturn("failing_tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{\"input\": 42}");
            when(observationContext.getError()).thenReturn(testError);

            handler.setContext("run-789", 5);
            handler.onStart(observationContext);
            handler.onError(observationContext);

            verify(listener).onToolExecutionFailed(
                    eq("run-789"),
                    eq(5),
                    toolCallCaptor.capture(),
                    eq(testError),
                    durationCaptor.capture()
            );

            AssistantMessage.ToolCall capturedToolCall = toolCallCaptor.getValue();
            assertThat(capturedToolCall.name()).isEqualTo("failing_tool");
            assertThat(capturedToolCall.arguments()).isEqualTo("{\"input\": 42}");
        }
    }

    @Nested
    @DisplayName("Listener exception handling")
    class ListenerExceptionHandling {

        @Test
        @DisplayName("should not propagate listener exceptions on start")
        void shouldNotPropagateExceptionsOnStart() {
            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");

            doThrow(new RuntimeException("Listener error"))
                    .when(listener).onToolExecutionStarted(anyString(), anyInt(), any());

            // Should not throw
            handler.onStart(observationContext);
        }

        @Test
        @DisplayName("should not propagate listener exceptions on stop")
        void shouldNotPropagateExceptionsOnStop() {
            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");
            when(observationContext.getToolCallResult()).thenReturn("result");

            doThrow(new RuntimeException("Listener error"))
                    .when(listener).onToolExecutionCompleted(anyString(), anyInt(), any(), anyString(), any());

            // Should not throw
            handler.onStop(observationContext);
        }

        @Test
        @DisplayName("should not propagate listener exceptions on error")
        void shouldNotPropagateExceptionsOnError() {
            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");
            when(observationContext.getError()).thenReturn(new RuntimeException("Tool error"));

            doThrow(new RuntimeException("Listener error"))
                    .when(listener).onToolExecutionFailed(anyString(), anyInt(), any(), any(), any());

            // Should not throw
            handler.onError(observationContext);
        }
    }

    @Nested
    @DisplayName("Multiple listeners")
    class MultipleListeners {

        @Mock
        private ToolCallListener listener2;

        @Test
        @DisplayName("should notify all listeners")
        void shouldNotifyAllListeners() {
            ToolCallObservationHandler multiHandler = new ToolCallObservationHandler(
                    List.of(listener, listener2)
            );

            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");

            multiHandler.onStart(observationContext);

            verify(listener).onToolExecutionStarted(anyString(), anyInt(), any());
            verify(listener2).onToolExecutionStarted(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("should continue notifying other listeners if one fails")
        void shouldContinueIfOneFails() {
            ToolCallObservationHandler multiHandler = new ToolCallObservationHandler(
                    List.of(listener, listener2)
            );

            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");

            doThrow(new RuntimeException("First listener failed"))
                    .when(listener).onToolExecutionStarted(anyString(), anyInt(), any());

            multiHandler.onStart(observationContext);

            // Second listener should still be called
            verify(listener2).onToolExecutionStarted(anyString(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("should create handler with single listener")
        void shouldCreateWithSingleListener() {
            ToolCallObservationHandler singleHandler = ToolCallObservationHandler.of(listener);

            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");

            singleHandler.onStart(observationContext);

            verify(listener).onToolExecutionStarted(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("should create handler with listener list")
        void shouldCreateWithListenerList() {
            ToolCallObservationHandler listHandler = ToolCallObservationHandler.of(List.of(listener));

            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");

            listHandler.onStart(observationContext);

            verify(listener).onToolExecutionStarted(anyString(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("Context switching")
    class ContextSwitching {

        @Test
        @DisplayName("should update context between calls")
        void shouldUpdateContextBetweenCalls() {
            when(toolDefinition.name()).thenReturn("tool");
            when(observationContext.getToolDefinition()).thenReturn(toolDefinition);
            when(observationContext.getToolCallArguments()).thenReturn("{}");

            handler.setContext("run-1", 1);
            handler.onStart(observationContext);
            verify(listener).onToolExecutionStarted(eq("run-1"), eq(1), any());

            reset(listener);

            handler.setContext("run-2", 5);
            handler.onStart(observationContext);
            verify(listener).onToolExecutionStarted(eq("run-2"), eq(5), any());
        }
    }
}
