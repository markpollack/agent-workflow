package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.VerdictReading;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static org.assertj.core.api.Assertions.*;

class ProductionBindingTest {
    public record Request(String id) {}
    public record First(String value) {}
    public record SecondInput(First first, Request original) {}
    public record Second(String value) {}
    public record Fourth(String value) {}
    public record FifthInput(Fourth latest, SecondInput earlier) {}
    public record Reply(String value) {}
    public record Missing(String value) {}
    public record Shared(String value) {}
    public record Receipt(String value) {}
    public record Hits(String value) {}
    public record Logs(String value) {}
    public record Artifacts(Hits hits, Logs logs) {}
    public record Indistinguishable(Hits first, Hits second) {}
    public record Renamed(@JsonProperty("wire_items") List<Hits> items) {}
    public record Envelope<T>(T value) {}
    public record NativeEnvelope(Request request, Verdict evidence, Interpretation reading) {}
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record UnsupportedShape(String value) {}
    public enum Route { LEFT, RIGHT }
    public enum Foreign { LEFT }

    private static Call call(String id, Type input, Type output) {
        return new Call(id, Op.declared(id, input, output));
    }
    private static End success() { return new End(Terminal.SUCCEEDED, null); }
    private static Compilation<?,?> build(Type input, Type output, Node... nodes) {
        return StructuredWorkflowCompiler.compile(new Definition<>("consumer", input, output, List.of(nodes),java.time.Duration.ofMinutes(5)));
    }
    private static Choice choice(String id, Type input, Arm... arms) {
        return new Choice(id, Op.declared(id, input, Route.class), null, List.of(arms));
    }
    private static Arm arm(Enum<?> outcome, Node... nodes) { return new Arm(outcome, List.of(nodes)); }
    private static Binding binding(Compilation<?,?> built, String operation) {
        return built.bindings().stream().filter(b -> b.operation().equals(operation)).findFirst().orElseThrow();
    }
    private static Parallel artifacts(Type output) {
        return new Parallel("files", output, true, List.of(
                new Member("hits", List.of(call("hits", Request.class, Hits.class))),
                new Member("logs", List.of(call("logs", Request.class, Logs.class)))));
    }

    @Test void rejectsUnboundOperationThroughTheCompleteBuild() {
        assertThatThrownBy(() -> build(Request.class, Reply.class,
                call("first", Request.class, First.class), call("report", Missing.class, Reply.class), success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void successfulTerminalCannotSelectAnOlderValueToMatchALyingOutputSummary() {
        assertThatThrownBy(() -> build(Request.class, Request.class,
                call("first", Request.class, First.class), success())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void directHeterogeneousCompletionReturnsItsValidatedProduct() {
        Compilation<?,?> built = build(Request.class, Artifacts.class, artifacts(Artifacts.class), success());
        assertThat(built.output()).isEqualTo(Artifacts.class);
        assertThat(built.products()).singleElement().satisfies(product -> {
            assertThat(product.result().type()).isEqualTo(Artifacts.class);
            assertThat(product.members()).extracting(Fact::type).containsExactly(Hits.class, Logs.class);
        });
        assertThatThrownBy(() -> build(Request.class, Request.class, artifacts(null), success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void directHomogeneousCompletionHasAnOrderedTypedList() {
        Parallel group = new Parallel("results", new ListType(Hits.class), true, List.of(
                new Member("second-label", List.of(call("alpha", Request.class, Hits.class))),
                new Member("first-label", List.of(call("beta", Request.class, Hits.class)))));
        Compilation<?,?> built = build(Request.class, new ListType(Hits.class), group, success());
        assertThat(built.products()).singleElement().satisfies(product -> {
            assertThat(product.result().type()).isEqualTo(new ListType(Hits.class));
            assertThat(product.members()).extracting(Fact::display).containsExactly("alpha.out", "beta.out");
        });
        assertThatThrownBy(() -> build(Request.class, new ListType(Logs.class), group, success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void productContractCannotGuessTwoIndistinguishableRolesByFieldOrder() {
        Parallel group = new Parallel("roles", Indistinguishable.class, true, List.of(
                new Member("first", List.of(call("one", Request.class, Hits.class))),
                new Member("second", List.of(call("two", Request.class, Hits.class)))));
        assertThatThrownBy(() -> build(Request.class, Indistinguishable.class, group, success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void closedEnumDomainRejectsMissingDuplicateForeignAndNullArms() {
        List<Arm> cases = List.of(arm(Route.LEFT, success()), arm(Route.RIGHT, success()));
        build(Request.class, Request.class, choice("complete", Request.class, cases.toArray(Arm[]::new)));
        assertThatThrownBy(() -> build(Request.class, Request.class, choice("missing", Request.class, cases.getFirst())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Request.class, choice("duplicate", Request.class,
                cases.getFirst(), cases.getFirst(), cases.getLast()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Request.class, choice("foreign", Request.class,
                cases.getFirst(), arm(Foreign.LEFT, success())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Request.class, choice("null", Request.class,
                cases.getFirst(), arm(null, success())))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void malformedBodiesBoundsAndTerminalIntentAreNotTrusted() {
        List<Node> malformed = List.of(
                new Parallel("empty", null, true, List.of()),
                new Parallel("policy", null, false, List.of(new Member("one", List.of(call("hit", Request.class, Hits.class))))),
                new Fan("fan", Request.class, 1, 1, true, List.of()),
                new Loop("loop", Op.named("test", Request.class, Boolean.class), 1, LimitPolicy.FAIL, List.of()),
                new Loop("zero", Op.named("test", Request.class, Boolean.class), 0, LimitPolicy.EXIT, List.of(call("next", Request.class, Request.class))),
                new End(null, "not-an-intent"), new End(Terminal.FAILED, null));
        for (Node node : malformed) {
            assertThatThrownBy(() -> build(Request.class, Request.class, node, success()))
                    .as("malformed %s", node).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void historicalInputUsesTheExactEarlierAssemblyAfterItsComponentChanges() {
        Compilation<?,?> built = build(Request.class, Reply.class,
                call("first", Request.class, First.class), call("second", SecondInput.class, Second.class),
                call("newFirst", Request.class, First.class), call("fourth", Second.class, Fourth.class),
                call("fifth", FifthInput.class, Reply.class), success());
        Fact earlier = binding(built, "second").input();
        Fact reused = binding(built, "fifth").input().components().get(1);
        assertThat(reused.identity()).isEqualTo(earlier.identity());
        assertThat(reused.components().getFirst().identity()).isEqualTo(binding(built, "first").output().identity());
        assertThat(reused.components().getFirst().identity()).isNotEqualTo(binding(built, "newFirst").output().identity());
    }

    @Test void compatibleButWrongReorderingIsDetectedByTheIndependentMappingOracle() {
        Compilation<?,?> changed = build(Request.class, Reply.class,
                call("first", Request.class, First.class), call("replacement", Request.class, First.class),
                call("second", SecondInput.class, Second.class), call("fourth", Second.class, Fourth.class),
                call("fifth", FifthInput.class, Reply.class), success());
        Fact observed = binding(changed, "second").input().components().getFirst();
        Fact expectedOriginal = binding(changed, "first").output();
        assertThat(observed.identity()).isEqualTo(binding(changed, "replacement").output().identity());
        assertThatThrownBy(() -> assertThat(observed.identity()).isEqualTo(expectedOriginal.identity()))
                .isInstanceOf(AssertionError.class);
    }

    @Test void earlierWholeInputIsAnAliasOfTheSelectedWholeValue() {
        Compilation<?,?> built = build(SecondInput.class, Reply.class,
                call("second", SecondInput.class, Second.class), call("fourth", Second.class, Fourth.class),
                call("fifth", FifthInput.class, Reply.class), success());
        Fact priorInput = binding(built, "second").input();
        assertThat(priorInput.components()).isEmpty();
        assertThat(binding(built, "fifth").input().components().get(1).identity()).isEqualTo(priorInput.identity());
    }

    @Test void independentSameTypedHistoryRemainsAmbiguousAfterCarrierChanges() {
        assertThatThrownBy(() -> build(Request.class, Reply.class,
                call("old", Request.class, Shared.class), call("fresh", Request.class, Shared.class),
                call("cleanup", Request.class, Receipt.class), call("consume", Shared.class, Reply.class), success()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambig");
        Compilation<?,?> current = build(Request.class, Reply.class,
                call("old", Request.class, Shared.class), call("fresh", Request.class, Shared.class),
                call("consume", Shared.class, Reply.class), success());
        assertThat(binding(current, "consume").input().identity()).isEqualTo(binding(current, "fresh").output().identity());
    }

    @Test void captureDoesNotEraseAnIndependentHistoricalRole() {
        assertThatThrownBy(() -> build(Request.class, Reply.class,
                call("old", Request.class, Shared.class), call("separator", Request.class, Receipt.class),
                choice("select", Request.class,
                        arm(Route.LEFT, call("left", Request.class, Shared.class)),
                        arm(Route.RIGHT, call("right", Request.class, Shared.class))),
                call("cleanup", Request.class, Receipt.class), call("consume", Shared.class, Reply.class), success()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambig");
    }

    @Test void newDefiniteProductionClearsAnEarlierMissingAssignment() {
        Compilation<?,?> built = build(Request.class, Reply.class,
                choice("optional", Request.class, arm(Route.LEFT, call("optionalValue", Request.class, Shared.class)), arm(Route.RIGHT)),
                call("definite", Request.class, Shared.class), call("cleanup", Request.class, Receipt.class),
                call("consume", Shared.class, Reply.class), success());
        assertThat(binding(built, "consume").input().identity()).isEqualTo(binding(built, "definite").output().identity());
    }

    @Test void timerPreservesAnUnresolvedCarrierUntilARealContinuationProducesAValue() {
        Compilation<?,?> built = build(Request.class, Reply.class,
                choice("optional", Request.class, arm(Route.LEFT, call("optionalValue", Request.class, Shared.class)), arm(Route.RIGHT)),
                new Timer("pause", Duration.ZERO), call("definite", Request.class, Reply.class), success());
        assertThat(binding(built, "definite").input().type()).isEqualTo(Request.class);
        assertThatThrownBy(() -> build(Request.class, Request.class,
                choice("optional", Request.class, arm(Route.LEFT, call("optionalValue", Request.class, Shared.class)), arm(Route.RIGHT)),
                new Timer("pause", Duration.ZERO), success())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void childAndSiblingPrivateFactsCannotEscapeTheirScope() {
        Definition<?,?> child = new Definition<>("child", Request.class, Reply.class,
                List.of(call("private", Request.class, First.class), call("finish", First.class, Reply.class), success()),java.time.Duration.ofMinutes(5));
        assertThatThrownBy(() -> build(Request.class, Receipt.class, new Child("child", child),
                call("leak", First.class, Receipt.class), success())).isInstanceOf(IllegalArgumentException.class);
        Parallel siblings = new Parallel("siblings", null, true, List.of(
                new Member("one", List.of(call("private", Request.class, First.class))),
                new Member("two", List.of(call("leak", First.class, Receipt.class)))));
        assertThatThrownBy(() -> build(Request.class, Receipt.class, siblings, success())).isInstanceOf(IllegalArgumentException.class);
        Definition<?,?> invalid = new Definition<>("invalid", Request.class, Reply.class,
                List.of(call("unbound", Missing.class, Reply.class), success()),java.time.Duration.ofMinutes(5));
        assertThatThrownBy(() -> build(Request.class, Reply.class, new Child("call", invalid), success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scopeIdentityUsesComponentsAndDuplicatePlacementsRefuse() {
        Compilation<?,?> built = build(Request.class, Reply.class,
                call("left/right", Request.class, First.class),
                choice("left", Request.class,
                        arm(Route.LEFT, call("right", Request.class, Reply.class), success()),
                        arm(Route.RIGHT, call("right", Request.class, Reply.class), success())));
        assertThat(built.bindings()).extracting(Binding::placement).doesNotHaveDuplicates();
        assertThat(built.graph().nodes()).extracting(node -> node.name()).doesNotHaveDuplicates();
        assertThatThrownBy(() -> build(Request.class, Reply.class,
                call("same", Request.class, First.class), call("same", First.class, Reply.class), success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void capExitIsExplicitAndNeverChangesFalseToTrueOrChangesDataType() {
        for (LimitPolicy policy : LimitPolicy.values()) {
            Compilation<?,?> built = build(Request.class, Shared.class,
                    call("initial", Request.class, Shared.class),
                    new Loop("bounded", Op.named("ready", Shared.class, Boolean.class), 2, policy,
                            List.of(call("next", Shared.class, Shared.class))), success());
            assertThat(built.loops()).singleElement().satisfies(loop -> {
                assertThat(loop.output()).isEqualTo(Shared.class);
                assertThat(loop.evaluate(false, 1)).isEqualTo(LoopExit.CONTINUE);
                assertThat(loop.evaluate(true, 2)).isEqualTo(LoopExit.TEST_TRUE);
                assertThat(loop.evaluate(false, 2)).isEqualTo(policy == LimitPolicy.EXIT ? LoopExit.CAP_REACHED : LoopExit.LIMIT_FAILURE);
                assertThatThrownBy(() -> loop.evaluate(false, 3)).isInstanceOf(IllegalArgumentException.class);
            });
        }
    }

    @Test void genericElementsUnsupportedTypesAndInvalidBoundsAreCheckedAtBuild() {
        assertThatThrownBy(() -> build(Request.class, Reply.class,
                call("list", Request.class, new ListType(Hits.class)), call("consume", new ListType(Logs.class), Reply.class), success()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Thread.class, call("thread", Request.class, Thread.class), success()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StructuredWorkflowCompiler.compile(new Definition<>("deadline", Request.class, Request.class,
                List.of(success()), Duration.ZERO))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Request.class, new Timer("negative", Duration.ofNanos(-1)), success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void theBuildPathUsesConfiguredCodecShapesAndNativeEvidenceContracts() {
        Type generic = new TypeRef<Envelope<Renamed>>() {}.type();
        assertThat(build(generic, Reply.class, call("read", generic, Reply.class), success()).input()).isEqualTo(generic);
        assertThat(build(NativeEnvelope.class, Reply.class,
                call("read", NativeEnvelope.class, Reply.class), success()).input()).isEqualTo(NativeEnvelope.class);
        assertThatThrownBy(() -> build(Request.class, UnsupportedShape.class,
                call("array", Request.class, UnsupportedShape.class), success()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported");
    }

    @Test void builtDefinitionCannotBeChangedThroughCallerOwnedCollections() {
        ArrayList<Node> body = new ArrayList<>(List.of(call("reply", Request.class, Reply.class), success()));
        ArrayList<Arm> arms = new ArrayList<>(List.of(new Arm(Route.LEFT, body), arm(Route.RIGHT, call("other", Request.class, Reply.class), success())));
        ArrayList<Node> nodes = new ArrayList<>(List.of(new Choice("route", Op.named("route", Request.class, Route.class), null, arms)));
        Compilation<?,?> built = StructuredWorkflowCompiler.compile(new Definition<>("snapshot", Request.class, Reply.class, nodes,java.time.Duration.ofMinutes(5)));
        int graphSize = built.graph().nodes().size();
        body.clear(); arms.clear(); nodes.clear();
        assertThat(built.definition().nodes()).hasSize(1);
        Choice frozen = (Choice) built.definition().nodes().getFirst();
        assertThat(frozen.arms()).hasSize(2);
        assertThat(frozen.arms().getFirst().nodes()).hasSize(2);
        assertThat(built.graph().nodes()).hasSize(graphSize);
        assertThatThrownBy(() -> built.definition().nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> built.metadata().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void emittedGraphHasUniqueClosedTopologyAndMetadataForEveryNode() {
        Compilation<?,?> built = build(Request.class, Artifacts.class, artifacts(Artifacts.class), success());
        HashSet<String> names = new HashSet<>();
        built.graph().nodes().forEach(node -> assertThat(names.add(node.name())).isTrue());
        assertThat(names).contains(built.graph().startNode(), built.graph().finishNode());
        built.graph().edges().forEach(edge -> {
            assertThat(names).contains(edge.from(), edge.to());
            assertThat(edge.condition()).isNotNull();
        });
        assertThat(built.metadata().keySet().stream().map(Placement::graphName).toList()).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test void nativeEvidenceBindingsAppearInTheSameGraphWithTheirActualContracts() {
        List<Arm> arms = java.util.Arrays.stream(VerdictReading.values())
                .map(reading -> arm(reading, call(reading.name(), Request.class, Reply.class), success())).toList();
        Compilation<?,?> built = build(Request.class, Reply.class,
                new Choice("quality", null, new Assessment<>("jury", Request.class), arms));
        var names = built.graph().nodes().stream().map(WorkflowNode::name).toList();
        for (Binding binding : built.bindings()) {
            assertThat(names).contains(binding.placement().graphName());
            Metadata metadata = built.metadata().get(binding.placement());
            assertThat(metadata.input()).isEqualTo(binding.input().type());
            assertThat(metadata.output()).isEqualTo(binding.output().type());
        }
        assertThat(built.bindings()).extracting(b -> b.output().type()).contains(Verdict.class, Interpretation.class);
        for (WorkflowNode node : built.graph().nodes()) {
            if (node instanceof WorkflowNode.DecisionNode decision && decision.joinNodeName() != null) {
                assertThat(names).contains(decision.joinNodeName());
            }
        }
    }

    @Test void aDeclaredWholeProductCannotSilentlyDropAMemberResult() {
        Parallel group = new Parallel("complete", Artifacts.class, true, List.of(
                new Member("hits", List.of(call("hits", Request.class, Hits.class))),
                new Member("logs", List.of(call("logs", Request.class, Logs.class))),
                new Member("receipt", List.of(call("receipt", Request.class, Receipt.class)))));
        assertThatThrownBy(() -> build(Request.class, Artifacts.class, group, success()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concreteParameterizedRecordInputIsAssembledWithoutErasingItsElementType() {
        Type envelope = new TypeRef<Envelope<Hits>>() {}.type();
        Compilation<?,?> built = build(Request.class, Reply.class, call("hits", Request.class, Hits.class),
                call("consume", envelope, Reply.class), success());
        assertThat(binding(built, "consume").input().type()).isEqualTo(envelope);
        assertThat(binding(built, "consume").input().components()).extracting(Fact::type).containsExactly(Hits.class);
    }

    @Test void soleContinuingArmPropagatesItsProvenStateReplacement() {
        Compilation<?,?> built = build(Request.class, Reply.class,
                call("initial", Request.class, Shared.class),
                choice("continue", Request.class, arm(Route.LEFT, call("advance", Shared.class, Shared.class)),
                        arm(Route.RIGHT, new End(Terminal.FAILED, "stopped"))),
                call("cleanup", Request.class, Receipt.class), call("consume", Shared.class, Reply.class), success());
        assertThat(binding(built, "consume").input().identity()).isEqualTo(binding(built, "advance").output().identity());
    }

    @Test void currentProductRolesPrecedeHistoryWithoutErasingItOrProjectingOrdinaryRecords() {
        Compilation<?,?> current = build(Request.class, Reply.class, call("old", Request.class, Hits.class),
                call("separator", Request.class, Receipt.class), artifacts(null), call("consume", Artifacts.class, Reply.class), success());
        assertThat(binding(current, "consume").input().components().getFirst().identity())
                .isEqualTo(binding(current, "hits").output().identity());
        assertThatThrownBy(() -> build(Request.class, Reply.class, call("old", Request.class, Hits.class),
                call("separator", Request.class, Receipt.class), artifacts(null), call("cleanup", Request.class, Receipt.class),
                call("consume", Artifacts.class, Reply.class), success())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambig");
        Parallel product = new Parallel("fresh", null, true, List.of(
                new Member("hits", List.of(call("freshHits", Artifacts.class, Hits.class))),
                new Member("logs", List.of(call("freshLogs", Artifacts.class, Logs.class)))));
        Compilation<?,?> whole = build(Artifacts.class, Reply.class, product, call("consume", Artifacts.class, Reply.class), success());
        assertThat(binding(whole, "consume").input().components()).extracting(Fact::display)
                .containsExactly("freshHits.out", "freshLogs.out");
        assertThatThrownBy(() -> build(Artifacts.class, Reply.class,
                call("projectRawRecord", Hits.class, Reply.class), success())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void internalProductDescriptorsCannotMasqueradeAsApplicationContracts() {
        Type internal = new ProductType(List.of(Hits.class, Logs.class));
        assertThatThrownBy(() -> build(internal, Reply.class, call("read", internal, Reply.class), success()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, internal, artifacts(null), success()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Reply.class, call("fake", Request.class, internal),
                call("reply", Request.class, Reply.class), success())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Reply.class, call("fake", internal, Reply.class), success()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Reply.class, artifacts(internal),
                call("reply", Request.class, Reply.class), success())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Request.class, Reply.class,
                new Fan("fake", internal, 2, 1, true, List.of(call("read", Request.class, Reply.class))), success()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
