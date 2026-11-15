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

import io.github.markpollack.workflow.strategy.TerminationStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Central abstraction for agentic loop patterns.
 * <p>
 * Uses Spring AI ChatClient directly - no adapters needed.
 * The loop orchestrates the generate → tool execution → judge cycle:
 * <ol>
 *   <li>Send user message to ChatClient with available tools</li>
 *   <li>Process tool calls if present</li>
 *   <li>Check termination conditions</li>
 *   <li>Repeat until termination condition is met</li>
 * </ol>
 *
 * <p>Configuration is provided at construction time (via builder pattern),
 * following the mini-swe-agent approach where config is a construction concern.
 *
 * <p>The type parameter R allows each loop implementation to return
 * its specific result type, providing type-safe access to pattern-specific
 * data without casting when the loop type is known at compile time.
 *
 * <p>All 8 loop patterns from academic research are implementable:
 * <ol>
 *   <li>Turn-Limited Multi-Condition (Gemini CLI, Claude CLI)</li>
 *   <li>Evaluator-Optimizer (Reflexion, mcp-agent)</li>
 *   <li>Status-Based State Machine (OpenHands, Embabel)</li>
 *   <li>Finish Tool Detection (ReAct, LangChain)</li>
 *   <li>Pre-Planned Workflow (AutoGPT+P, AgentVerse)</li>
 *   <li>Generator/Yield (LATS, async patterns)</li>
 *   <li>Exception-Driven (fallback patterns)</li>
 *   <li>Polling with Sleep (background tasks)</li>
 * </ol>
 *
 * @param <R> The specific result type this loop returns (must implement LoopResult)
 */
public interface LoopPattern<R extends LoopResult> {

    /**
     * Executes the loop with the given prompt.
     * <p>
     * This is a synchronous, blocking call. The loop runs until a termination
     * condition is met (max turns, timeout, finish tool, score threshold, etc.).
     * <p>
     * Configuration is provided at construction time via the builder pattern.
     *
     * @param userMessage the user message to process
     * @param chatClient the Spring AI ChatClient to use
     * @param tools available tool callbacks
     * @return the loop result with pattern-specific data
     */
    R execute(
            String userMessage,
            ChatClient chatClient,
            List<ToolCallback> tools
    );

    /**
     * Returns the termination strategy for this loop.
     */
    TerminationStrategy terminationStrategy();

    /**
     * Returns the loop type identifier.
     */
    LoopType loopType();

    /**
     * Enumeration of the 8 loop patterns identified in academic research.
     */
    enum LoopType {
        /** Pattern 1: Turn limit + finish detection + score threshold */
        TURN_LIMITED_MULTI_CONDITION,
        /** Pattern 2: Actor-Evaluator-Reflector cycle (Reflexion) */
        EVALUATOR_OPTIMIZER,
        /** Pattern 3: Explicit state transitions */
        STATUS_BASED_STATE_MACHINE,
        /** Pattern 4: Special tool signals completion */
        FINISH_TOOL_DETECTION,
        /** Pattern 5: PDDL-style pre-planned steps */
        PRE_PLANNED_WORKFLOW,
        /** Pattern 6: Async iteration with yield */
        GENERATOR_YIELD,
        /** Pattern 7: Try-catch with fallback */
        EXCEPTION_DRIVEN,
        /** Pattern 8: Background polling */
        POLLING_WITH_SLEEP
    }
}
