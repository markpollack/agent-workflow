package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.patterns.graph.NodeMetrics;
import io.github.markpollack.workflow.patterns.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowGraphTest {

    private final Step<String, String> dummyStep = (ctx, in) -> in;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void shouldBuildGraphWithThreeNodesAndTwoEdges() {
        WorkflowNode fetchNode = WorkflowNode.deterministic("fetch", dummyStep);
        WorkflowNode analyzeNode = WorkflowNode.agent("analyze", dummyStep);
        WorkflowNode reportNode = WorkflowNode.deterministic("report", dummyStep);

        WorkflowEdge edge1 = WorkflowEdge.of("fetch", "analyze");
        WorkflowEdge edge2 = WorkflowEdge.of("analyze", "report");

        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "pr-review",
                List.of(fetchNode, analyzeNode, reportNode),
                List.of(edge1, edge2),
                "fetch",
                "report"
        );

        assertThat(graph.name()).isEqualTo("pr-review");
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.startNode()).isEqualTo("fetch");
        assertThat(graph.finishNode()).isEqualTo("report");
        assertThat(graph.metrics()).isEmpty();
    }

    @Test
    void nodesShouldBeImmutable() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test", List.of(WorkflowNode.deterministic("a", dummyStep)),
                List.of(), "a", "a"
        );

        assertThat(graph.nodes()).isUnmodifiable();
    }

    @Test
    void edgesShouldBeImmutable() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(WorkflowNode.deterministic("a", dummyStep)),
                List.of(WorkflowEdge.of("a", "a")),
                "a", "a"
        );

        assertThat(graph.edges()).isUnmodifiable();
    }

    // -------------------------------------------------------------------------
    // withMetrics
    // -------------------------------------------------------------------------

    @Test
    void withMetricsShouldProduceNewRecordOriginalUnchanged() {
        WorkflowGraph<String, String> original = WorkflowGraph.of(
                "test",
                List.of(WorkflowNode.deterministic("a", dummyStep)),
                List.of(), "a", "a"
        );

        Map<String, NodeMetrics> metricsMap = Map.of(
                "a", NodeMetrics.deterministic(Duration.ofMillis(100))
        );
        WorkflowGraph<String, String> enriched = original.withMetrics(metricsMap);

        assertThat(original.metrics()).isEmpty();
        assertThat(enriched.metrics()).hasSize(1);
        assertThat(enriched.metrics().get("a").duration()).isEqualTo(Duration.ofMillis(100));
        assertThat(enriched.name()).isEqualTo(original.name());
        assertThat(enriched.nodes()).isEqualTo(original.nodes());
    }

    // -------------------------------------------------------------------------
    // findNode / edgesFrom / nodeByName
    // -------------------------------------------------------------------------

    @Test
    void findNodeShouldReturnNodeByName() {
        WorkflowNode node = WorkflowNode.agent("analyze", dummyStep);
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test", List.of(node), List.of(), "analyze", "analyze"
        );

        assertThat(graph.findNode("analyze")).contains(node);
        assertThat(graph.findNode("missing")).isEmpty();
    }

    @Test
    void edgesFromShouldReturnOutgoingEdges() {
        WorkflowEdge e1 = WorkflowEdge.of("a", "b");
        WorkflowEdge e2 = WorkflowEdge.of("a", "c");
        WorkflowEdge e3 = WorkflowEdge.of("b", "c");

        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(
                        WorkflowNode.deterministic("a", dummyStep),
                        WorkflowNode.deterministic("b", dummyStep),
                        WorkflowNode.deterministic("c", dummyStep)
                ),
                List.of(e1, e2, e3),
                "a", "c"
        );

        assertThat(graph.edgesFrom("a")).containsExactly(e1, e2);
        assertThat(graph.edgesFrom("b")).containsExactly(e3);
        assertThat(graph.edgesFrom("c")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // WorkflowNode (sealed interface)
    // -------------------------------------------------------------------------

    @Test
    void deterministicNodeShouldHaveCorrectType() {
        WorkflowNode node = WorkflowNode.deterministic("fetch", dummyStep);

        assertThat(node.name()).isEqualTo("fetch");
        assertThat(node.type()).isEqualTo(NodeType.DETERMINISTIC);
        assertThat(node).isInstanceOf(WorkflowNode.StepNode.class);
        assertThat(((WorkflowNode.StepNode) node).step()).isEqualTo(dummyStep);
    }

    @Test
    void agentNodeShouldHaveCorrectType() {
        WorkflowNode node = WorkflowNode.agent("analyze", dummyStep);

        assertThat(node.type()).isEqualTo(NodeType.AGENT);
    }

    // -------------------------------------------------------------------------
    // WorkflowEdge (EdgeCondition-based)
    // -------------------------------------------------------------------------

    @Test
    void unconditionalEdgeShouldBeUnconditional() {
        WorkflowEdge edge = WorkflowEdge.of("a", "b");

        assertThat(edge.isUnconditional()).isTrue();
        assertThat(edge.condition()).isInstanceOf(EdgeCondition.Unconditional.class);
        assertThat(edge.label()).isNull();
    }

    @Test
    void conditionalEdgeShouldCarryCondition() {
        WorkflowEdge edge = WorkflowEdge.conditional("a", "b",
                new EdgeCondition.BooleanGuard(true), "true");

        assertThat(edge.isUnconditional()).isFalse();
        assertThat(edge.condition()).isEqualTo(new EdgeCondition.BooleanGuard(true));
        assertThat(edge.label()).isEqualTo("true");
    }

    @Test
    void labeledEdgeShouldCarryLabel() {
        WorkflowEdge edge = WorkflowEdge.labeled("a", "b", "on-success");

        assertThat(edge.label()).isEqualTo("on-success");
        assertThat(edge.isUnconditional()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Lookup methods
    // -------------------------------------------------------------------------

    @Test
    void unconditionalSuccessorShouldFindSequentialEdge() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(WorkflowNode.deterministic("a", dummyStep),
                        WorkflowNode.deterministic("b", dummyStep)),
                List.of(WorkflowEdge.sequence("a", "b")),
                "a", "b"
        );

        assertThat(graph.unconditionalSuccessor("a")).isEqualTo("b");
        assertThat(graph.unconditionalSuccessor("b")).isNull();
    }

    @Test
    void hasDirectEdgeShouldReturnCorrectly() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(WorkflowNode.deterministic("a", dummyStep),
                        WorkflowNode.deterministic("b", dummyStep)),
                List.of(WorkflowEdge.sequence("a", "b")),
                "a", "b"
        );

        assertThat(graph.hasDirectEdge("a", "b")).isTrue();
        assertThat(graph.hasDirectEdge("b", "a")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Static factory
    // -------------------------------------------------------------------------

    @Test
    void ofFactoryShouldCreateGraphWithEmptyMetrics() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "simple",
                List.of(WorkflowNode.deterministic("only", dummyStep)),
                List.of(),
                "only", "only"
        );

        assertThat(graph.metrics()).isEmpty();
    }
}
