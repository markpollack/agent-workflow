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
package io.github.markpollack.workflow.patterns.graph;

/**
 * Declares whether a graph node is deterministic or agent-driven.
 * <p>
 * This is the key distinction in the Blueprint Engine (Pattern 5):
 * <ul>
 *   <li>{@link #DETERMINISTIC} — pure computation; no LLM, no tokens, predictable cost,
 *       guaranteed termination. Suitable for: API calls, file I/O, data transformation,
 *       compilation, test runners, metrics collection.</li>
 *   <li>{@link #AGENT} — LLM-driven; consumes tokens, variable cost, may loop internally
 *       with tool calls. Suitable for: code analysis, implementation, evaluation,
 *       summarization, planning.</li>
 * </ul>
 * <p>
 * This declaration enables:
 * <ul>
 *   <li>Per-node cost tracking (deterministic nodes always have zero token cost)</li>
 *   <li>Differentiated timeout policies (agent nodes need longer timeouts)</li>
 *   <li>Blueprint visualization (deterministic = rectangle, agent = rounded rectangle)</li>
 *   <li>Cost estimation before execution (sum agent node costs × expected token rates)</li>
 * </ul>
 */
public enum NodeType {

    /**
     * No LLM calls, no tokens consumed, predictable cost, guaranteed termination.
     * A function graph node is always {@code DETERMINISTIC}.
     */
    DETERMINISTIC,

    /**
     * Uses an LLM, consumes tokens, variable cost, may need timeout/termination.
     * A loop graph node is always {@code AGENT}.
     */
    AGENT
}
