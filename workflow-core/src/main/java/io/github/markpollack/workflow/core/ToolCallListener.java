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
package io.github.markpollack.workflow.core;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.List;

/**
 * Listener for tool call events, enabling observability of tool execution.
 * <p>
 * By using user-controlled tool execution (internalToolExecutionEnabled=false),
 * the agentic loop has visibility into each tool call, allowing detailed tracking
 * of what tools are being invoked, their arguments, and results.
 * <p>
 * Implementations can use this for:
 * <ul>
 *   <li>Logging and debugging</li>
 *   <li>Metrics and cost tracking</li>
 *   <li>Security auditing</li>
 *   <li>Progress reporting to users</li>
 * </ul>
 */
public interface ToolCallListener {

    /**
     * Called before a batch of tool calls is executed.
     *
     * @param runId the run identifier
     * @param turn the current turn number
     * @param toolCalls the list of tool calls to execute
     */
    default void onToolCallsRequested(String runId, int turn, List<AssistantMessage.ToolCall> toolCalls) {}

    /**
     * Called before a single tool is executed.
     *
     * @param runId the run identifier
     * @param turn the current turn number
     * @param toolCall the tool call about to execute
     */
    default void onToolExecutionStarted(String runId, int turn, AssistantMessage.ToolCall toolCall) {}

    /**
     * Called after a single tool execution completes successfully.
     *
     * @param runId the run identifier
     * @param turn the current turn number
     * @param toolCall the tool call that was executed
     * @param result the result from the tool
     * @param duration how long the execution took
     */
    default void onToolExecutionCompleted(
            String runId,
            int turn,
            AssistantMessage.ToolCall toolCall,
            String result,
            Duration duration
    ) {}

    /**
     * Called when a tool execution fails.
     *
     * @param runId the run identifier
     * @param turn the current turn number
     * @param toolCall the tool call that failed
     * @param error the error that occurred
     * @param duration how long before failure
     */
    default void onToolExecutionFailed(
            String runId,
            int turn,
            AssistantMessage.ToolCall toolCall,
            Throwable error,
            Duration duration
    ) {}

    /**
     * Called after all tool calls in a batch have been executed.
     *
     * @param runId the run identifier
     * @param turn the current turn number
     * @param toolCallCount number of tools that were called
     * @param successCount number of successful executions
     * @param totalDuration total time for all tool executions
     */
    default void onToolCallsCompleted(
            String runId,
            int turn,
            int toolCallCount,
            int successCount,
            Duration totalDuration
    ) {}

    /**
     * A no-op listener that does nothing.
     */
    static ToolCallListener noop() {
        return new ToolCallListener() {};
    }
}
