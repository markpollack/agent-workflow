package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.core.ContextKey;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.patterns.graph.NodeType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.ConsensusStrategy;
import io.github.markpollack.judge.jury.Decision;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.Judgment;
import io.github.markpollack.judge.result.JudgmentStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GateTest {

    // -------------------------------------------------------------------------
    // Gate routing
    // -------------------------------------------------------------------------

    @Nested
    class Routing {

        @Test
        void gateShouldRouteToPassStepOnPass() {
            Gate<Object> alwaysPass = (ctx, output) -> GateAssessment.decided(GateDecision.PASS);

            String result = Workflow.<String, String>define("gate-pass")
                    .step(Step.named("prepare", (ctx, in) -> ((String) in).toUpperCase()))
                    .gate(alwaysPass)
                        .onPass(Step.named("commit", (ctx, in) -> "committed: " + in))
                        .onFail(Step.named("retry", (ctx, in) -> "retried: " + in))
                    .end()
                    .run("hello");

            assertThat(result).isEqualTo("committed: HELLO");
        }

        @Test
        void gateShouldRouteToFailStepOnFail() {
            Gate<Object> alwaysFail = (ctx, output) -> GateAssessment.decided(GateDecision.FAIL);

            String result = Workflow.<String, String>define("gate-fail")
                    .step(Step.named("prepare", (ctx, in) -> ((String) in).toUpperCase()))
                    .gate(alwaysFail)
                        .onPass(Step.named("commit", (ctx, in) -> "committed: " + in))
                        .onFail(Step.named("retry", (ctx, in) -> "retried: " + in))
                    .end()
                    .run("hello");

            assertThat(result).isEqualTo("retried: HELLO");
        }

        @Test
        void gateShouldRouteToTimeoutStepOnTimeout() {
            Gate<Object> alwaysTimeout = (ctx, output) -> GateAssessment.decided(GateDecision.TIMEOUT);

            String result = Workflow.<String, String>define("gate-timeout")
                    .gate(alwaysTimeout)
                        .onPass(Step.named("deploy", (ctx, in) -> "deployed"))
                        .onFail(Step.named("log", (ctx, in) -> "logged"))
                        .onTimeout(Step.named("escalate", (ctx, in) -> "escalated"))
                    .end()
                    .run("request");

            assertThat(result).isEqualTo("escalated");
        }

        @Test
        void gateShouldPassInputThroughToRouteStep() {
            // The gate evaluates the output but does NOT transform it —
            // the routed step receives the same value that entered the gate
            Gate<Object> scoreGate = (ctx, output) -> {
                double score = (Double) output;
                return GateAssessment.decided(score >= 0.8 ? GateDecision.PASS : GateDecision.FAIL);
            };

            String result = Workflow.<Double, String>define("score-gate")
                    .gate(scoreGate)
                        .onPass(Step.named("accept", (ctx, in) -> "accepted: " + in))
                        .onFail(Step.named("reject", (ctx, in) -> "rejected: " + in))
                    .end()
                    .run(0.9);

            assertThat(result).isEqualTo("accepted: 0.9");
        }

        @Test
        void gateShouldWorkInMiddleOfPipeline() {
            Gate<Object> gate = (ctx, output) -> GateAssessment.decided(
                    ((String) output).contains("GOOD") ? GateDecision.PASS : GateDecision.FAIL);

            String result = Workflow.<String, String>define("pipeline-gate")
                    .step(Step.named("classify", (ctx, in) -> in + "-GOOD"))
                    .gate(gate)
                        .onPass(Step.named("approve", (ctx, in) -> "approved: " + in))
                        .onFail(Step.named("reject", (ctx, in) -> "rejected: " + in))
                    .end()
                    .then(Step.named("finalize", (ctx, in) -> in + "!"))
                    .run("input");

            assertThat(result).isEqualTo("approved: input-GOOD!");
        }
    }

    // -------------------------------------------------------------------------
    // Topology
    // -------------------------------------------------------------------------

    @Nested
    class Topology {

        @Test
        void gateShouldProduceExplodedGraph() {
            Gate<Object> gate = (ctx, output) -> GateAssessment.decided(GateDecision.PASS);

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("gate-topo")
                    .gate(gate)
                        .onPass(Step.named("pass-step", (ctx, in) -> in))
                        .onFail(Step.named("fail-step", (ctx, in) -> in))
                    .end()
                    .compile();

            // GateNode + StepNode(pass) + StepNode(fail) + JoinNode = 4
            assertThat(graph.nodes()).hasSize(4);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.GateNode.class);
            assertThat(graph.nodes().get(1)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(2)).isInstanceOf(WorkflowNode.StepNode.class);
            assertThat(graph.nodes().get(3)).isInstanceOf(WorkflowNode.JoinNode.class);
        }

        @Test
        void gateWithTimeoutShouldProduceThreePathGraph() {
            Gate<Object> gate = (ctx, output) -> GateAssessment.decided(GateDecision.PASS);

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("gate-3path")
                    .gate(gate)
                        .onPass(Step.named("pass", (ctx, in) -> in))
                        .onFail(Step.named("fail", (ctx, in) -> in))
                        .onTimeout(Step.named("timeout", (ctx, in) -> in))
                    .end()
                    .compile();

            // GateNode + 3 StepNodes + JoinNode = 5
            assertThat(graph.nodes()).hasSize(5);
            assertThat(graph.nodes().get(0)).isInstanceOf(WorkflowNode.GateNode.class);
            assertThat(graph.nodes().get(4)).isInstanceOf(WorkflowNode.JoinNode.class);
        }

        @Test
        void gateNodeShouldCarryJoinNodeName() {
            Gate<Object> gate = (ctx, output) -> GateAssessment.decided(GateDecision.PASS);

            WorkflowGraph<String, String> graph = Workflow.<String, String>define("gate-join")
                    .gate(gate)
                        .onPass(Step.named("pass", (ctx, in) -> in))
                    .end()
                    .compile();

            WorkflowNode.GateNode gateNode = (WorkflowNode.GateNode) graph.nodes().get(0);
            WorkflowNode.JoinNode joinNode = (WorkflowNode.JoinNode) graph.nodes().get(graph.nodes().size() - 1);
            assertThat(gateNode.joinNodeName()).isEqualTo(joinNode.name());
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    class EdgeCases {

        @Test
        void gateWithoutOnPassShouldThrow() {
            Gate<Object> gate = (ctx, output) -> GateAssessment.decided(GateDecision.PASS);

            assertThatThrownBy(() ->
                    Workflow.<String, String>define("no-pass")
                            .gate(gate)
                            .end())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("onPass");
        }

        @Test
        void gatePassOnlyShouldWork() {
            Gate<Object> gate = (ctx, output) -> GateAssessment.decided(GateDecision.PASS);

            String result = Workflow.<String, String>define("pass-only")
                    .gate(gate)
                        .onPass(Step.named("proceed", (ctx, in) -> "ok: " + in))
                    .end()
                    .run("test");

            assertThat(result).isEqualTo("ok: test");
        }
    }

    // -------------------------------------------------------------------------
    // JudgeGate
    // -------------------------------------------------------------------------

    @Nested
    class JudgeGateTests {

        @Test
        void judgeGateShouldPassWhenScoreAboveThreshold() {
            Jury jury = mockJuryReturningScore(0.9);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate evaluation"));

            String result = Workflow.<String, String>define("judge-pass")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run("output");

            assertThat(result).isEqualTo("committed");
        }

        @Test
        void judgeGateShouldFailWhenScoreBelowThreshold() {
            Jury jury = mockJuryReturningScore(0.5);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate evaluation"));

            String result = Workflow.<String, String>define("judge-fail")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run("output");

            assertThat(result).isEqualTo("retried");
        }

        @Test
        void judgeGateFailShouldWriteVerdictToContext() {
            Jury jury = mockJuryReturningScore(0.3);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate evaluation"));
            AtomicReference<Object> capturedVerdict = new AtomicReference<>();

            Workflow.<String, String>define("verdict-feedback")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> {
                            capturedVerdict.set(ctx.get(AgentContext.JUDGE_VERDICT).orElse(null));
                            return "retried";
                        }))
                    .end()
                    .run("output");

            assertThat(capturedVerdict.get()).isNotNull();
            assertThat(capturedVerdict.get()).isInstanceOf(Verdict.class);
        }
    }

    // -------------------------------------------------------------------------
    // TieredGate
    // -------------------------------------------------------------------------

    @Nested
    class TieredGateTests {

        @Test
        void tieredGateShouldPassOnHighScore() {
            Jury jury = mockJuryReturningScore(0.95);
            TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered gate evaluation"));

            GateDecision decision = decisionOf(gate.evaluate(AgentContext.create(), "output"));
            assertThat(decision).isEqualTo(GateDecision.PASS);
        }

        @Test
        void tieredGateShouldEscalateOnBorderlineScore() {
            Jury jury = mockJuryReturningScore(0.75);
            TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered gate evaluation"));

            GateDecision decision = decisionOf(gate.evaluate(AgentContext.create(), "output"));
            assertThat(decision).isEqualTo(GateDecision.ESCALATE);
        }

        @Test
        void tieredGateShouldFailOnLowScore() {
            Jury jury = mockJuryReturningScore(0.3);
            TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered gate evaluation"));

            GateDecision decision = decisionOf(gate.evaluate(AgentContext.create(), "output"));
            assertThat(decision).isEqualTo(GateDecision.FAIL);
        }
    }

    // -------------------------------------------------------------------------
    // Status-first gate outcomes (Act AJ-14 watched guards)
    // -------------------------------------------------------------------------

    /**
     * A jury reports an outcome; it does not always report a measurement. These three guards
     * pin what a gate does with an aggregate that carries no score, so that "no measurement"
     * can never be read as "measured zero".
     *
     * <p>Each assertion below is stated in terms of the gate's own decision, so it survives the
     * agent-judge score-model change unchanged; only the way the fixture verdict is constructed
     * moves with the dependency.
     *
     * <p>Semantics per the ratified boundary: a status-only PASS/FAIL uses the derived
     * {@code 1.0}/{@code 0.0} view; ABSTAIN, NOT_APPLICABLE and ERROR made no assessment at all,
     * so none reaches a threshold comparison. The tiered gate's first tier escalates an abstention
     * to its human tier rather than terminating; an exclusion is not escalated. The typed error vocabulary for the inconclusive
     * and failed-evaluation paths is minted later and deliberately not coined here — what the
     * gate returns says only that no finding exists, and the verdict it carries says why.
     */
    @Nested
    class StatusFirstOutcomes {

        @Test
        void statusOnlyPassMustNotDecideFail() {
            Jury jury = mockJuryReturning(statusOnlyPass());
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate evaluation"));

            String result = Workflow.<String, String>define("status-only-pass")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run("output");

            assertThat(result).isEqualTo("committed");
        }

        @Test
        void statusOnlyPassMustClearEvenTheHighTier() {
            Jury jury = mockJuryReturning(statusOnlyPass());
            TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered"));

            assertThat(decisionOf(gate.evaluate(AgentContext.create(), "output")))
                    .isEqualTo(GateDecision.PASS);
        }

        @Test
        void abstainMustNotBeComparedAgainstAThreshold() {
            // lowThreshold is above zero, so a laundered 0.0 would land on FAIL and be visible.
            Jury jury = mockJuryReturning(abstained());

            JudgeGate<Object> judgeGate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate"));
            assertThat(judgeGate.evaluate(AgentContext.create(), "output"))
                    .asInstanceOf(type(GateAssessment.Inconclusive.class))
                    .satisfies(inconclusive -> assertThat(inconclusive.verdict().aggregated().status())
                            .isEqualTo(JudgmentStatus.ABSTAIN));

            TieredGate<Object> tieredGate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered"));
            assertThat(decisionOf(tieredGate.evaluate(AgentContext.create(), "output")))
                    .isEqualTo(GateDecision.ESCALATE);
        }

        @Test
        void errorMustNotBeComparedAgainstAThreshold() {
            Jury jury = mockJuryReturning(errored());

            JudgeGate<Object> judgeGate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate"));
            assertThat(judgeGate.evaluate(AgentContext.create(), "output"))
                    .asInstanceOf(type(GateAssessment.Inconclusive.class))
                    .satisfies(inconclusive -> assertThat(inconclusive.verdict().aggregated().status())
                            .isEqualTo(JudgmentStatus.ERROR));

            TieredGate<Object> tieredGate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered"));
            assertThat(tieredGate.evaluate(AgentContext.create(), "output"))
                    .asInstanceOf(type(GateAssessment.Inconclusive.class))
                    .satisfies(inconclusive -> assertThat(inconclusive.verdict().aggregated().status())
                            .isEqualTo(JudgmentStatus.ERROR));
        }

        @Test
        void notApplicableMustNeitherPassNorEscalate() {
            // An exclusion is not a finding: routing it to PASS would let a jury clear the gate by
            // declining the question, and it is not a borderline result for the human tier.
            Jury jury = mockJuryReturning(notApplicable());

            JudgeGate<Object> judgeGate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate"));
            assertThat(judgeGate.evaluate(AgentContext.create(), "output"))
                    .asInstanceOf(type(GateAssessment.Inconclusive.class))
                    .satisfies(inconclusive -> assertThat(inconclusive.verdict().aggregated().status())
                            .isEqualTo(JudgmentStatus.NOT_APPLICABLE));

            TieredGate<Object> tieredGate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered"));
            assertThat(tieredGate.evaluate(AgentContext.create(), "output"))
                    .asInstanceOf(type(GateAssessment.Inconclusive.class))
                    .satisfies(inconclusive -> assertThat(inconclusive.verdict().aggregated().status())
                            .isEqualTo(JudgmentStatus.NOT_APPLICABLE));
        }
    }

    @Nested
    class AssessmentInvariants {

        @Test
        void decidedRequiresADecision() {
            assertThatThrownBy(() -> new GateAssessment.Decided(null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("decision");
        }

        @Test
        void inconclusiveRequiresAVerdict() {
            assertThatThrownBy(() -> new GateAssessment.Inconclusive(null, "no finding"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("verdict");
        }

        @Test
        void inconclusiveRequiresAReason() {
            Verdict verdict = verdictOf(abstained());

            assertThatThrownBy(() -> new GateAssessment.Inconclusive(verdict, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reason");
        }

        @Test
        void inconclusiveRejectsAVerdictThatReachedAFinding() {
            Verdict verdict = verdictOf(statusOnlyPass());

            assertThatThrownBy(() -> new GateAssessment.Inconclusive(verdict, "not actually inconclusive"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PASS");
        }
    }

    // -------------------------------------------------------------------------
    // Status-first outcomes, proved where the engine actually runs them
    // -------------------------------------------------------------------------

    /**
     * The guards above call {@link Gate#evaluate} directly, which is enough to pin what a gate
     * <em>concludes</em> and nothing at all about what the engine <em>does</em> with that
     * conclusion. These run the same four aggregates through {@link WorkflowExecutor} and assert
     * the evidence the engine is obliged to preserve — the verdict trail, the singular verdict,
     * and the recorded transition — as well as the outward behaviour.
     *
     * <p>The distinction is the whole point of the correction: a value that reaches the engine
     * and terminates it without a recorded transition has lost its evidence, and a direct call
     * to {@code evaluate} cannot tell the difference.
     */
    @Nested
    class ExecutorPathOutcomes {

        @Test
        void statusOnlyPassRoutesAndIsRecordedThroughTheExecutor() {
            Run run = runGate(mockJuryReturning(statusOnlyPass()), 0.8);

            assertThat(run.output()).isEqualTo("committed");
            assertThat(verdictTrail(run.contextAfter())).hasSize(1);
            assertThat(gateTransition(run).label()).isEqualTo("pass");
        }

        @Test
        void abstainKeepsItsEvidenceBeforeTheExecutorTerminates() {
            Run run = runGate(mockJuryReturning(abstained()), 0.8);

            // Terminated on a signal of its own, not on a violated invariant.
            assertThat(run.thrown())
                    .asInstanceOf(type(GateAssessmentException.class))
                    .satisfies(signal -> assertThat(signal.verdict().aggregated().status())
                            .isEqualTo(JudgmentStatus.ABSTAIN));
            // The verdict reached the run's evidence rather than escaping with the exception.
            assertThat(run.contextAfter()).isNotNull();
            assertThat(verdictTrail(run.contextAfter())).hasSize(1);
            assertThat(run.contextAfter().get(AgentContext.JUDGE_VERDICT)).isPresent();
            // And the gate node's transition was recorded before the run ended.
            assertThat(gateTransition(run).label()).isEqualTo("inconclusive");
        }

        @Test
        void errorKeepsItsEvidenceBeforeTheExecutorTerminates() {
            Run run = runGate(mockJuryReturning(errored()), 0.8);

            assertThat(run.thrown())
                    .asInstanceOf(type(GateAssessmentException.class))
                    .satisfies(signal -> assertThat(signal.verdict().aggregated().status())
                            .isEqualTo(JudgmentStatus.ERROR));
            assertThat(run.contextAfter()).isNotNull();
            assertThat(verdictTrail(run.contextAfter())).hasSize(1);
            assertThat(run.contextAfter().get(AgentContext.JUDGE_VERDICT)).isPresent();
            assertThat(gateTransition(run).label()).isEqualTo("inconclusive");
        }

        /**
         * The engine's error/retry path is reached, not merely thrown past. A judging
         * sub-workflow is an ordinary step to its parent, so an inconclusive evaluation inside it
         * arrives at the parent's error edge like any other step failure — and it arrives with the
         * gate's transition already in the shared trace and the verdict on the signal itself.
         */
        @Test
        void anAbstentionReachesRecoveryWithItsExactJudgeEvidence() {
            assertRecoveryReceivesJudgeEvidence(abstained(), JudgmentStatus.ABSTAIN);
        }

        @Test
        void anEvaluationErrorReachesRecoveryWithItsExactJudgeEvidence() {
            assertRecoveryReceivesJudgeEvidence(errored(), JudgmentStatus.ERROR);
        }

        @Test
        void aNotApplicableExclusionReachesRecoveryWithItsExactJudgeEvidence() {
            assertRecoveryReceivesJudgeEvidence(notApplicable(), JudgmentStatus.NOT_APPLICABLE);
        }

        private void assertRecoveryReceivesJudgeEvidence(Judgment aggregate, JudgmentStatus expectedStatus) {
            Verdict expectedVerdict = verdictOf(aggregate);
            Jury jury = mock(Jury.class);
            when(jury.vote(any())).thenReturn(expectedVerdict);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate"));
            ContextKey<String> partialState = ContextKey.of("child.partialState", String.class);
            Step<String, String> writePartialState = new Step<>() {
                @Override
                public String execute(AgentContext ctx, String input) {
                    return input;
                }

                @Override
                public AgentContext updateContext(AgentContext ctx, String output) {
                    return ctx.mutate().with(partialState, "must not escape").build();
                }
            };
            Workflow<String, String> judging = Workflow.<String, String>define("judging")
                    .step(writePartialState)
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .build();
            AtomicReference<Object> recoveredVerdict = new AtomicReference<>();
            AtomicReference<List<Object>> recoveredTrail = new AtomicReference<>();
            AtomicReference<String> recoveredPartialState = new AtomicReference<>();

            Run run = execute(Workflow.<String, String>define("parent")
                    .step(judging)
                    .onError(GateAssessmentException.class, Step.named("recover", (ctx, in) -> {
                        recoveredVerdict.set(ctx.get(AgentContext.JUDGE_VERDICT).orElse(null));
                        recoveredTrail.set(ctx.get(AgentContext.JUDGE_VERDICTS).orElse(null));
                        recoveredPartialState.set(ctx.get(partialState).orElse("absent"));
                        return "recovered";
                    }))
                    .compile());

            assertThat(run.thrown()).isNull();
            assertThat(run.output()).isEqualTo("recovered");
            assertThat(recoveredVerdict.get()).isSameAs(expectedVerdict);
            assertThat(recoveredTrail.get()).containsExactly(expectedVerdict);
            assertThat(((Verdict) recoveredVerdict.get()).aggregated().status()).isEqualTo(expectedStatus);
            assertThat(recoveredPartialState.get()).isEqualTo("absent");
            // The gate's transition was recorded before the signal travelled to the error edge.
            assertThat(gateTransition(run).label()).isEqualTo("inconclusive");
        }

        @Test
        void tieredAbstainEscalatesThroughTheExecutor() {
            Run run = runTieredGate(mockJuryReturning(abstained()));

            assertThat(run.output()).isEqualTo("escalated");
            assertThat(verdictTrail(run.contextAfter())).hasSize(1);
            assertThat(run.contextAfter().get(AgentContext.JUDGE_VERDICT)).isPresent();
            assertThat(gateTransition(run).label()).isEqualTo("escalate");
        }
    }

    // -------------------------------------------------------------------------
    // Reflector
    // -------------------------------------------------------------------------

    @Nested
    class ReflectorTests {

        @Test
        void reflectorShouldWriteFeedbackToContextOnFail() {
            Jury jury = mockJuryReturningScore(0.3);
            JudgeGate<Object> gate = new JudgeGate<>(jury, 0.8, JudgeGate.defaultContextMapper("Gate evaluation"));
            AtomicReference<String> capturedReflection = new AtomicReference<>();

            Step<?, ?> reflector = Step.named("reflector",
                    (ctx, verdict) -> "Improve: score was too low");

            Workflow.<String, String>define("reflector-test")
                    .gate(gate)
                        .withReflector(reflector)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> {
                            capturedReflection.set(
                                    ctx.get(AgentContext.JUDGE_REFLECTION).orElse(null));
                            return "retried";
                        }))
                    .end()
                    .run("output");

            assertThat(capturedReflection.get()).isEqualTo("Improve: score was too low");
        }
    }

    // -------------------------------------------------------------------------
    // Context mapper — real (non-mocked) jury whose judge reads metadata
    // -------------------------------------------------------------------------

    @Nested
    class ContextMapperTests {

        // Lifts the typed gate output into JudgmentContext metadata so a real metadata-reading
        // judge can be fed by JudgeGate — impossible before the mapper became a required gate input.
        private final BiFunction<AgentContext, Boolean, JudgmentContext> buildPassedMapper =
                (ctx, buildPassed) -> JudgmentContext.builder()
                        .goal("build health")
                        .metadata("buildPassed", buildPassed)
                        .executionTime(Duration.ZERO)
                        .startedAt(Instant.now())
                        .build();

        @Test
        void judgeGateWithMapperShouldPassWhenMetadataReadingJudgePasses() {
            JudgeGate<Boolean> gate = new JudgeGate<>(metadataReadingJury(), 0.5, buildPassedMapper);

            String result = Workflow.<Boolean, String>define("mapper-pass")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run(true);

            assertThat(result).isEqualTo("committed");
        }

        @Test
        void judgeGateWithMapperShouldFailWhenMetadataReadingJudgeFails() {
            JudgeGate<Boolean> gate = new JudgeGate<>(metadataReadingJury(), 0.5, buildPassedMapper);

            String result = Workflow.<Boolean, String>define("mapper-fail")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> "committed"))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run(false);

            assertThat(result).isEqualTo("retried");
        }

        @Test
        void judgeGateShouldRecordVerdictTrailOnPass() {
            JudgeGate<Boolean> gate = new JudgeGate<>(metadataReadingJury(), 0.5, buildPassedMapper);
            AtomicReference<Object> capturedTrail = new AtomicReference<>();

            Workflow.<Boolean, String>define("verdict-trail")
                    .gate(gate)
                        .onPass(Step.named("commit", (ctx, in) -> {
                            capturedTrail.set(ctx.get(AgentContext.JUDGE_VERDICTS).orElse(null));
                            return "committed";
                        }))
                        .onFail(Step.named("retry", (ctx, in) -> "retried"))
                    .end()
                    .run(true);

            // Recorded even though the gate PASSED — the old code only wrote a verdict on FAIL.
            assertThat(capturedTrail.get()).isInstanceOf(List.class);
            assertThat((List<?>) capturedTrail.get()).hasSize(1);
        }

        // A real SimpleJury (not Mockito) whose single judge reads metadata("buildPassed").
        private Jury metadataReadingJury() {
            Judge buildPassedJudge = context -> {
                boolean passed = Boolean.TRUE.equals(context.metadata().get("buildPassed"));
                return Judgment.scored(passed ? 1.0 : 0.0)
                        .passingAt(0.5)
                        .reasoning("buildPassed=" + passed)
                        .build();
            };
            return SimpleJury.builder().votingStrategy(new ConsensusStrategy()).judge(buildPassedJudge).build();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** One executor run: what came out, what the run left behind, and what ended it. */
    private record Run(Object output, AgentContext contextAfter, List<StepTransition> trace,
                       Throwable thrown) {}

    /** Runs a judge gate through {@link WorkflowExecutor}, capturing evidence whether or not it completes. */
    private static Run runGate(Jury jury, double threshold) {
        JudgeGate<Object> gate = new JudgeGate<>(jury, threshold, JudgeGate.defaultContextMapper("Gate"));
        return execute(Workflow.<String, String>define("executor-path")
                .gate(gate)
                    .onPass(Step.named("commit", (ctx, in) -> "committed"))
                    .onFail(Step.named("retry", (ctx, in) -> "retried"))
                .end()
                .compile());
    }

    /** Runs a tiered gate through the executor, over a graph carrying the escalation route. */
    private static Run runTieredGate(Jury jury) {
        TieredGate<Object> gate = new TieredGate<>(jury, 0.9, 0.6, JudgeGate.defaultContextMapper("Tiered"));
        return execute(withEscalateRoute(Workflow.<String, String>define("tiered-executor-path")
                .gate(gate)
                    .onPass(Step.named("commit", (ctx, in) -> "committed"))
                    .onFail(Step.named("retry", (ctx, in) -> "retried"))
                .end()
                .compile()));
    }

    private static Run execute(WorkflowGraph<String, String> graph) {
        TraceRecorder recorder = TraceRecorder.inMemory();
        AgentContext ctx = AgentContext.create();
        AtomicReference<AgentContext> contextAfter = new AtomicReference<>();
        Object output = null;
        Throwable thrown = null;
        try {
            output = new WorkflowExecutor(recorder).execute(graph, ctx, "output", null, contextAfter);
        } catch (Throwable t) {
            thrown = t;
        }
        return new Run(output, contextAfter.get(), recorder.getTrace(ctx.runId()), thrown);
    }

    /**
     * Adds the one edge the v1 DSL cannot author: {@code GateBuilder} has {@code onPass},
     * {@code onFail} and {@code onTimeout} and no {@code onEscalate}, so a tiered gate's
     * escalation has no declared route. The executor routes {@code GateMatch(ESCALATE)} like any
     * other condition, so the graph is assembled here rather than declared.
     */
    private static WorkflowGraph<String, String> withEscalateRoute(WorkflowGraph<String, String> graph) {
        WorkflowNode.GateNode gateNode = (WorkflowNode.GateNode) graph.nodes().stream()
                .filter(WorkflowNode.GateNode.class::isInstance)
                .findFirst()
                .orElseThrow();
        List<WorkflowNode> nodes = new ArrayList<>(graph.nodes());
        nodes.add(new WorkflowNode.StepNode("escalate", NodeType.DETERMINISTIC,
                Step.named("escalate", (ctx, in) -> "escalated")));
        List<WorkflowEdge> edges = new ArrayList<>(graph.edges());
        edges.add(WorkflowEdge.conditional(gateNode.name(), "escalate",
                new EdgeCondition.GateMatch(GateDecision.ESCALATE), "escalate"));
        edges.add(WorkflowEdge.sequence("escalate", gateNode.joinNodeName()));
        return new WorkflowGraph<>(graph.name(), nodes, edges, graph.startNode(), graph.finishNode(),
                graph.metrics());
    }

    /** The decision an evaluation reached, failing readably when it reached none. */
    private static GateDecision decisionOf(GateAssessment evaluation) {
        assertThat(evaluation).isInstanceOf(GateAssessment.Decided.class);
        return ((GateAssessment.Decided) evaluation).decision();
    }

    private static List<?> verdictTrail(AgentContext ctx) {
        return ctx.get(AgentContext.JUDGE_VERDICTS).orElse(List.of());
    }

    private static StepTransition gateTransition(Run run) {
        return run.trace().stream()
                .filter(t -> t.toStep().startsWith("gate-"))
                .findFirst()
                .orElse(null);
    }

    /** An aggregate that reports PASS and measured nothing. */
    private static Judgment statusOnlyPass() {
        return Judgment.builder()
                .pass()
                .reasoning("The judge reported an outcome without a measurement")
                .build();
    }

    /** An aggregate that cast no vote. */
    private static Judgment abstained() {
        return Judgment.builder()
                .abstain()
                .reasoning("The judge does not apply to this subject")
                .build();
    }

    /** An aggregate declaring that the criterion should not have been asked of this subject. */
    private static Judgment notApplicable() {
        return Judgment.notApplicable("The subject has no Java source for this criterion to apply to");
    }

    /** An aggregate from a judge that never reached a finding. */
    private static Judgment errored() {
        return Judgment.builder()
                .error()
                .reasoning("The judge could not complete its evaluation")
                .build();
    }

    private static Jury mockJuryReturning(Judgment aggregate) {
        Jury jury = mock(Jury.class);
        Verdict verdict = verdictOf(aggregate);
        when(jury.vote(any())).thenReturn(verdict);
        return jury;
    }

    private static Verdict verdictOf(Judgment aggregate) {
        return Verdict.builder().aggregated(aggregate).individual(List.of()).decision(Decision.own()).build();
    }

    private static Jury mockJuryReturningScore(double score) {
        Jury jury = mock(Jury.class);
        Verdict verdict = Verdict.builder()
                .aggregated(Judgment.scored(score)
                        .passingAt(0.5)
                        .reasoning("Score: " + score)
                        .build())
                .individual(List.of())
                .decision(Decision.own())
                .build();
        when(jury.vote(any())).thenReturn(verdict);
        return jury;
    }
}
