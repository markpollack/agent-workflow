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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeAndMetricsTest {

    @Nested
    class NodeTypeDeclarations {

        @Test
        void functionGraphNode_shouldBeDeterministic() {
            GraphNode<String, String> node = GraphNode.of("fn", (ctx, input) -> input);
            assertThat(node.nodeType()).isEqualTo(NodeType.DETERMINISTIC);
        }

        @Test
        void defaultNodeType_shouldBeAgent() {
            // Custom GraphNode implementation without override uses the default
            GraphNode<String, String> customNode = new GraphNode<>() {
                @Override
                public String name() { return "custom"; }

                @Override
                public String execute(GraphContext context, String input) { return input; }
            };
            assertThat(customNode.nodeType()).isEqualTo(NodeType.AGENT);
        }
    }

    @Nested
    class NodeMetricsFactories {

        @Test
        void deterministic_shouldHaveZeroTokensAndCost() {
            NodeMetrics metrics = NodeMetrics.deterministic(Duration.ofMillis(42));

            assertThat(metrics.nodeType()).isEqualTo(NodeType.DETERMINISTIC);
            assertThat(metrics.duration()).isEqualTo(Duration.ofMillis(42));
            assertThat(metrics.tokensUsed()).isZero();
            assertThat(metrics.costUsd()).isZero();
        }

        @Test
        void agent_shouldStoreTokensAndCost() {
            NodeMetrics metrics = NodeMetrics.agent(Duration.ofMillis(500), 1200L, 0.0036);

            assertThat(metrics.nodeType()).isEqualTo(NodeType.AGENT);
            assertThat(metrics.duration()).isEqualTo(Duration.ofMillis(500));
            assertThat(metrics.tokensUsed()).isEqualTo(1200L);
            assertThat(metrics.costUsd()).isEqualTo(0.0036);
        }
    }

    @Nested
    class GraphResultNodeMetrics {

        @Test
        void completed_withoutMetrics_shouldReturnEmptyMap() {
            GraphResult<String> result = GraphResult.completed("out", java.util.List.of("a", "finish"), 2,
                    Duration.ofMillis(10));

            assertThat(result.nodeMetrics()).isEmpty();
        }

        @Test
        void completed_withMetrics_shouldExposeMetricsMap() {
            Map<String, NodeMetrics> metrics = Map.of(
                    "transform", NodeMetrics.deterministic(Duration.ofMillis(5)),
                    "analyze", NodeMetrics.agent(Duration.ofMillis(400), 800L, 0.002));

            GraphResult<String> result = GraphResult.completed("out", java.util.List.of("transform", "analyze", "finish"),
                    3, Duration.ofMillis(410), metrics);

            assertThat(result.nodeMetrics()).hasSize(2);
            assertThat(result.nodeMetrics().get("transform").nodeType()).isEqualTo(NodeType.DETERMINISTIC);
            assertThat(result.nodeMetrics().get("analyze").tokensUsed()).isEqualTo(800L);
        }

        @Test
        void stuckInNode_shouldReturnEmptyMetrics() {
            GraphResult<String> result = GraphResult.stuckInNode("bad-node", java.util.List.of("bad-node"), 1,
                    Duration.ofMillis(5));

            assertThat(result.nodeMetrics()).isEmpty();
        }
    }

    @Nested
    class PerNodeMetricsCollection {

        @Test
        void graphExecution_shouldCollectMetricsForAllNodes() {
            GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("metrics-test")
                    .startNode("start")
                    .finishNode("finish")
                    .node("transform", (GraphContext ctx, String input) -> input.toUpperCase())
                    .edge("start").to("transform")
                    .edge("transform").to("finish")
                    .build();

            GraphResult<String> result = strategy.execute("hello");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.nodeMetrics()).containsKeys("start", "transform", "finish");
        }

        @Test
        void graphExecution_shouldTagFunctionNodesAsDeterministic() {
            GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("node-type-test")
                    .startNode("start")
                    .finishNode("finish")
                    .node("step", (ctx, input) -> input + "-processed")
                    .edge("start").to("step")
                    .edge("step").to("finish")
                    .build();

            GraphResult<String> result = strategy.execute("input");

            assertThat(result.nodeMetrics().get("start").nodeType()).isEqualTo(NodeType.DETERMINISTIC);
            assertThat(result.nodeMetrics().get("step").nodeType()).isEqualTo(NodeType.DETERMINISTIC);
            assertThat(result.nodeMetrics().get("finish").nodeType()).isEqualTo(NodeType.DETERMINISTIC);
        }

        @Test
        void graphExecution_eachNodeMetrics_shouldHavePositiveDuration() {
            GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("duration-test")
                    .startNode("start")
                    .finishNode("finish")
                    .node("work", (ctx, input) -> input)
                    .edge("start").to("work")
                    .edge("work").to("finish")
                    .build();

            GraphResult<String> result = strategy.execute("x");

            result.nodeMetrics().values().forEach(m ->
                    assertThat(m.duration()).isGreaterThanOrEqualTo(Duration.ZERO));
        }

        @Test
        void graphExecution_deterministicNodes_shouldHaveZeroTokensAndCost() {
            GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("zero-cost-test")
                    .startNode("start")
                    .finishNode("finish")
                    .edge("start").to("finish")
                    .build();

            GraphResult<String> result = strategy.execute("input");

            result.nodeMetrics().values().forEach(m -> {
                assertThat(m.tokensUsed()).isZero();
                assertThat(m.costUsd()).isZero();
            });
        }
    }
}
