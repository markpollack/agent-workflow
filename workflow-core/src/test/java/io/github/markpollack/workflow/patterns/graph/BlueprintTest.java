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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlueprintTest {

    @Nested
    class SequentialBlueprint {

        @Test
        void singleNode_shouldExecuteAndReturn() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("single")
                    .deterministic("only", (GraphContext ctx, String input) -> input.toUpperCase())
                    .sequential()
                    .build();

            GraphResult<String> result = blueprint.execute("hello");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).isEqualTo("HELLO");
        }

        @Test
        void linearPipeline_shouldExecuteNodesInOrder() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("pipeline")
                    .deterministic("step1", "Prefix with A", (GraphContext ctx, String input) -> "A:" + input)
                    .deterministic("step2", "Prefix with B", (GraphContext ctx, String input) -> "B:" + input)
                    .deterministic("step3", "Prefix with C", (GraphContext ctx, String input) -> "C:" + input)
                    .sequential()
                    .build();

            GraphResult<String> result = blueprint.execute("x");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).isEqualTo("C:B:A:x");
            assertThat(result.pathTaken()).containsExactly("step1", "step2", "step3");
        }

        @Test
        void linearPipeline_shouldCollectPerNodeMetrics() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("metrics-check")
                    .deterministic("step1", (GraphContext ctx, String input) -> input + "-1")
                    .deterministic("step2", (GraphContext ctx, String input) -> input + "-2")
                    .sequential()
                    .build();

            GraphResult<String> result = blueprint.execute("x");

            assertThat(result.nodeMetrics()).containsKeys("step1", "step2");
            result.nodeMetrics().values().forEach(m ->
                    assertThat(m.nodeType()).isEqualTo(NodeType.DETERMINISTIC));
        }

        @Test
        void noNodes_shouldThrowOnBuild() {
            assertThatThrownBy(() -> Blueprint.<String, String>builder("empty").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empty");
        }
    }

    @Nested
    class BlueprintNodeDeclarations {

        @Test
        void deterministicNode_shouldHaveCorrectType() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("typed")
                    .deterministic("fn", "A function node", (GraphContext ctx, String input) -> input)
                    .sequential()
                    .build();

            assertThat(blueprint.nodes()).hasSize(1);
            assertThat(blueprint.nodes().get(0).nodeType()).isEqualTo(NodeType.DETERMINISTIC);
            assertThat(blueprint.nodes().get(0).description()).isEqualTo("A function node");
        }

        @Test
        void nodeDeclarationOrder_shouldMatchDeclarationOrder() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("order")
                    .deterministic("alpha", (GraphContext ctx, String input) -> input)
                    .deterministic("beta", (GraphContext ctx, String input) -> input)
                    .deterministic("gamma", (GraphContext ctx, String input) -> input)
                    .sequential()
                    .build();

            List<String> nodeNames = blueprint.nodes().stream()
                    .map(BlueprintNode::name)
                    .toList();
            assertThat(nodeNames).containsExactly("alpha", "beta", "gamma");
        }

        @Test
        void nodeDescriptions_shouldBeEmptyByDefault() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("no-desc")
                    .deterministic("step", (GraphContext ctx, String input) -> input)
                    .sequential()
                    .build();

            assertThat(blueprint.nodes().get(0).description()).isEmpty();
        }
    }

    @Nested
    class BlueprintEdgeRouting {

        @Test
        void conditionalEdge_shouldRouteBasedOnOutput() {
            // check → pass (if "ok") → done
            //       → fail (otherwise) → done
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("conditional")
                    .deterministic("check", (GraphContext ctx, String input) -> input.startsWith("ok") ? "ok:pass" : "not-ok")
                    .deterministic("pass", (GraphContext ctx, String input) -> "PASSED:" + input)
                    .deterministic("fail", (GraphContext ctx, String input) -> "FAILED:" + input)
                    .deterministic("done", (GraphContext ctx, String input) -> input)
                    .edge(BlueprintEdge.when("check", "pass", (String s) -> s.startsWith("ok")))
                    .edge(BlueprintEdge.of("check", "fail"))
                    .edge(BlueprintEdge.of("pass", "done"))
                    .edge(BlueprintEdge.of("fail", "done"))
                    .build();

            GraphResult<String> okResult = blueprint.execute("ok-input");
            assertThat(okResult.isSuccess()).isTrue();
            assertThat(okResult.output()).startsWith("PASSED:");

            GraphResult<String> failResult = blueprint.execute("bad-input");
            assertThat(failResult.isSuccess()).isTrue();
            assertThat(failResult.output()).startsWith("FAILED:");
        }

        @Test
        void blueprint_toGraphCompositionStrategy_shouldReturnSameEngine() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("bridge")
                    .deterministic("only", (GraphContext ctx, String input) -> input.toLowerCase())
                    .sequential()
                    .build();

            GraphCompositionStrategy<String, String> strategy = blueprint.toGraphCompositionStrategy();
            GraphResult<String> result = strategy.execute("HELLO");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).isEqualTo("hello");
        }

        @Test
        void blueprint_toString_shouldIncludeName() {
            Blueprint<String, String> blueprint = Blueprint.<String, String>builder("my-pipeline")
                    .deterministic("step", (GraphContext ctx, String input) -> input)
                    .sequential()
                    .build();

            assertThat(blueprint.toString()).contains("my-pipeline");
        }
    }

    @Nested
    class BlueprintEdgeRecord {

        @Test
        void unconditionalEdge_shouldAlwaysMatch() {
            BlueprintEdge edge = BlueprintEdge.of("a", "b");

            assertThat(edge.from()).isEqualTo("a");
            assertThat(edge.to()).isEqualTo("b");
            assertThat(edge.condition().test("anything")).isTrue();
        }

        @Test
        void conditionalEdge_shouldRespectPredicate() {
            BlueprintEdge edge = BlueprintEdge.when("a", "b", (String s) -> s.length() > 3);

            assertThat(edge.condition().test("long-string")).isTrue();
            assertThat(edge.condition().test("hi")).isFalse();
        }
    }
}
