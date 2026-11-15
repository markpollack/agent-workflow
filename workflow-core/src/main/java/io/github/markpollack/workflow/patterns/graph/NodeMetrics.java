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

import io.github.markpollack.workflow.core.NodeType;
import java.time.Duration;
import java.util.Objects;

/**
 * Execution metrics for a single {@link GraphNode} within a graph run.
 * <p>
 * Collected by {@link GraphCompositionStrategy} during execution and surfaced
 * via {@link GraphResult#nodeMetrics()}.
 * <p>
 * For {@link NodeType#DETERMINISTIC} nodes, {@code tokensUsed} is always 0 and
 * {@code costUsd} is always 0.0. For {@link NodeType#AGENT} nodes, token counts
 * and costs are extracted from the {@link io.github.markpollack.workflow.core.LoopResult}
 * when available.
 *
 * @param nodeType   the type of node that produced these metrics
 * @param duration   wall-clock time the node took to execute
 * @param tokensUsed tokens consumed during this node's execution (0 for DETERMINISTIC)
 * @param costUsd    estimated USD cost for this node (0.0 for DETERMINISTIC)
 */
public record NodeMetrics(NodeType nodeType, Duration duration, long tokensUsed, double costUsd) {

    public NodeMetrics {
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
    }

    /**
     * Creates metrics for a deterministic node.
     * Tokens and cost are always zero — deterministic nodes make no LLM calls.
     *
     * @param duration how long the node took
     * @return NodeMetrics with zero token cost
     */
    public static NodeMetrics deterministic(Duration duration) {
        return new NodeMetrics(NodeType.DETERMINISTIC, duration, 0L, 0.0);
    }

    /**
     * Creates metrics for an agent node with token tracking.
     *
     * @param duration   how long the node took
     * @param tokensUsed total tokens consumed
     * @param costUsd    estimated USD cost
     * @return NodeMetrics with LLM cost data
     */
    public static NodeMetrics agent(Duration duration, long tokensUsed, double costUsd) {
        return new NodeMetrics(NodeType.AGENT, duration, tokensUsed, costUsd);
    }
}
