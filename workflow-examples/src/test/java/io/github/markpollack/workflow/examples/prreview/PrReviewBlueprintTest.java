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
package io.github.markpollack.workflow.examples.prreview;

import io.github.markpollack.workflow.patterns.graph.BlueprintNode;
import io.github.markpollack.workflow.patterns.graph.GraphResult;
import io.github.markpollack.workflow.core.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrReviewBlueprintTest {

    private PrReviewBlueprint pipeline;

    @BeforeEach
    void setUp() {
        // Stub implementations — no live API calls
        pipeline = new PrReviewBlueprint(
                state -> state.withRawData("{\"number\":42,\"title\":\"Test PR\"}"),
                state -> state.withConversationAnalysis("Stub conversation analysis"),
                state -> state.withRiskAssessment("Stub risk assessment: LOW"),
                state -> state.withSolutionAssessment("Stub solution assessment: GOOD")
        );
    }

    @Nested
    class PipelineExecution {

        @Test
        void run_shouldSucceedAndPopulateAllAnalysisFields() {
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            assertThat(result.isSuccess()).isTrue();
            PrReviewState state = result.output();
            assertThat(state.rawPrData()).isEqualTo("{\"number\":42,\"title\":\"Test PR\"}");
            assertThat(state.conversationAnalysis()).isEqualTo("Stub conversation analysis");
            assertThat(state.riskAssessment()).isEqualTo("Stub risk assessment: LOW");
            assertThat(state.solutionAssessment()).isEqualTo("Stub solution assessment: GOOD");
        }

        @Test
        void run_shouldProduceMarkdownReport() {
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            String report = result.output().markdownReport();
            assertThat(report).contains("# PR Review Report");
            assertThat(report).contains("Stub conversation analysis");
            assertThat(report).contains("Stub risk assessment: LOW");
            assertThat(report).contains("Stub solution assessment: GOOD");
        }

        @Test
        void run_shouldTraverseAllFiveNodes() {
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            assertThat(result.pathTaken()).containsExactly(
                    "fetch-pr", "analyze", "assess-risk", "assess-solutions", "assemble-report");
        }

        @Test
        void run_shouldCollectPerNodeMetrics() {
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            assertThat(result.nodeMetrics()).containsKeys(
                    "fetch-pr", "analyze", "assess-risk", "assess-solutions", "assemble-report");
        }
    }

    @Nested
    class NodeTypeDeclarations {

        @Test
        void fetchPrAndAssembleReport_shouldBeDeterministic() {
            List<BlueprintNode> nodes = pipeline.blueprint().nodes();

            BlueprintNode fetchPr = findNode(nodes, "fetch-pr");
            BlueprintNode assembleReport = findNode(nodes, "assemble-report");

            assertThat(fetchPr.nodeType()).isEqualTo(NodeType.DETERMINISTIC);
            assertThat(assembleReport.nodeType()).isEqualTo(NodeType.DETERMINISTIC);
        }

        @Test
        void analysisNodes_shouldBeAgent() {
            List<BlueprintNode> nodes = pipeline.blueprint().nodes();

            assertThat(findNode(nodes, "analyze").nodeType()).isEqualTo(NodeType.AGENT);
            assertThat(findNode(nodes, "assess-risk").nodeType()).isEqualTo(NodeType.AGENT);
            assertThat(findNode(nodes, "assess-solutions").nodeType()).isEqualTo(NodeType.AGENT);
        }

        @Test
        void pipeline_shouldHaveFiveNodes() {
            assertThat(pipeline.blueprint().nodes()).hasSize(5);
        }

        @Test
        void fetchPrMetrics_shouldBeDeterministicType() {
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            assertThat(result.nodeMetrics().get("fetch-pr").nodeType())
                    .isEqualTo(NodeType.DETERMINISTIC);
            assertThat(result.nodeMetrics().get("assemble-report").nodeType())
                    .isEqualTo(NodeType.DETERMINISTIC);
        }

        @Test
        void agentNodeMetrics_shouldBeAgentType() {
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            assertThat(result.nodeMetrics().get("analyze").nodeType()).isEqualTo(NodeType.AGENT);
            assertThat(result.nodeMetrics().get("assess-risk").nodeType()).isEqualTo(NodeType.AGENT);
            assertThat(result.nodeMetrics().get("assess-solutions").nodeType()).isEqualTo(NodeType.AGENT);
        }
    }

    @Nested
    class ControlFlowEquivalence {

        @Test
        void executionOrder_matchesPrReviewAnalyzerMethodCallOrder() {
            // PrReviewAnalyzer calls: loadPrData → conversationAnalysis → riskAssessment
            //                          → solutionAssessment → generateReport
            // Blueprint path:          fetch-pr → analyze → assess-risk
            //                          → assess-solutions → assemble-report
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            assertThat(result.pathTaken()).containsExactly(
                    "fetch-pr", "analyze", "assess-risk", "assess-solutions", "assemble-report");
            assertThat(result.iterations()).isEqualTo(5);
        }

        @Test
        void stateIsThreadedExplicitly_notHeldByOrchestrator() {
            // Each node receives the accumulated PrReviewState and returns an updated copy.
            // This is the key structural difference from PrReviewAnalyzer, which holds
            // state in local variables.
            GraphResult<PrReviewState> result = pipeline.run("https://github.com/org/repo/pull/42");

            // Final state contains all intermediate results — no orchestrator needed
            PrReviewState finalState = result.output();
            assertThat(finalState.rawPrData()).isNotNull();
            assertThat(finalState.conversationAnalysis()).isNotNull();
            assertThat(finalState.riskAssessment()).isNotNull();
            assertThat(finalState.solutionAssessment()).isNotNull();
            assertThat(finalState.markdownReport()).isNotNull();
        }
    }

    private BlueprintNode findNode(List<BlueprintNode> nodes, String name) {
        return nodes.stream()
                .filter(n -> n.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + name));
    }
}
