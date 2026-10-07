package io.github.markpollack.workflow.flows.r1probe.grammar;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.markpollack.judge.verdict.Verdict.Conclusion;
import org.junit.jupiter.api.Test;

import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarFixtures.*;
import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarFixtures.CreditOutcome.*;
import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.*;
import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.Terminal.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuralTotalityProbeTest {
    private static End success() { return new End(SUCCEEDED, ""); }
    private static Call call(String id) { return new Call(id, Request.class, Reply.class); }
    private static void refusal(String fragment, Runnable action) {
        var error = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(error.getMessage().contains(fragment), error.getMessage());
    }
    private static void rawRefusal(String fragment, Node... nodes) {
        refusal(fragment, () -> validateRaw("raw", Request.class, List.of(nodes)));
    }

    @Test void missingTerminalAndEmptyRootRefuseAtBuild() {
        refusal("missing terminal", () -> define("missing").then(first).build());
        refusal("missing terminal", () -> define("empty", Request.class).build());
    }

    @Test void missingDuplicateForeignAndNullDecisionOutcomesRefuseAtBuild() {
        refusal("missing or foreign outcomes", () -> define("missing").then(createOrder).decision("credit", credit)
                .when(RESERVED).then(approve).terminate(SUCCEEDED).end().build());
        refusal("duplicate outcome", () -> define("duplicate").then(createOrder).decision("credit", credit)
                .when(RESERVED).then(approve).terminate(SUCCEEDED)
                .when(RESERVED).then(approve).terminate(SUCCEEDED)
                .when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build());
        refusal("foreign outcome", () -> define("foreign").then(createOrder).decision("credit", credit)
                .when(ForeignOutcome.RESERVED).then(approve).terminate(SUCCEEDED)
                .when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build());
        refusal("foreign outcome", () -> define("null").then(createOrder).decision("credit", credit)
                .when(null).then(approve).terminate(SUCCEEDED)
                .when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build());
    }

    @Test void eachMissingNativeReadingAndEveryDuplicateRefusesAtBuild() {
        for (Conclusion omitted : Conclusion.values()) {
            var choice = define("missing-" + omitted).then(outline).verdict("quality", jury);
            for (Conclusion reading : Conclusion.values()) {
                if (reading != omitted) choice = choice.when(reading).then(story).terminate(SUCCEEDED);
            }
            var finalChoice = choice;
            refusal("missing or foreign outcomes", () -> finalChoice.end().build());
        }
        for (Conclusion duplicated : Conclusion.values()) {
            var choice = define("duplicate-" + duplicated).then(outline).verdict("quality", jury);
            for (Conclusion reading : Conclusion.values()) {
                choice = choice.when(reading).then(story).terminate(SUCCEEDED);
            }
            var finalChoice = choice.when(duplicated).then(story).terminate(SUCCEEDED);
            refusal("duplicate outcome", () -> finalChoice.end().build());
        }
    }

    @Test void emptyArmsNeedAValidContinuation() {
        var valid = define("continued").then(createOrder).decision("credit", credit)
                .when(RESERVED).when(UNAVAILABLE).end().then(approve).terminate(SUCCEEDED).build();
        assertEquals(Set.of(OrderReply.class), valid.successfulOutputs());
        refusal("dangling path", () -> define("dangling").then(createOrder).decision("credit", credit)
                .when(RESERVED).when(UNAVAILABLE).end().build());
        refusal("dangling path", () -> define("one-dangling").then(createOrder).decision("credit", credit)
                .when(RESERVED).then(approve).terminate(SUCCEEDED).when(UNAVAILABLE).end().build());
    }

    @Test void implicitMemberAndLoopContinuationsDoNotEndTheRoot() {
        refusal("missing terminal", () -> define("group").then(first).parallel("checks").allSuccessful()
                .branch("one").then(second).end().build());
        refusal("missing terminal", () -> define("loop").then(initial).repeatUntil("ready", ready)
                .maxIterations(2).then(revise).end().build());
        var built = define("group").then(first).parallel("checks").allSuccessful()
                .branch("one").then(second).end().then(fifth).terminate(SUCCEEDED).build();
        assertEquals(Set.of(Reply.class), built.successfulOutputs());
    }

    @Test void zeroAndNegativeBoundsAndInvalidConcurrencyRefuseAtBuild() {
        for (int value : List.of(0, -1)) {
            refusal("positive bounds", () -> define("loop").then(initial).repeatUntil("ready", ready)
                    .maxIterations(value).then(revise).end().then(publish).terminate(SUCCEEDED).build());
            refusal("positive bounds", () -> define("fan").then(split).forEach("chunks").maxItems(value)
                    .allSuccessful().then(process).end().then(assemble).terminate(SUCCEEDED).build());
            refusal("positive bounds", () -> define("fan").then(split).forEach("chunks").maxItems(3)
                    .maxInFlight(value).allSuccessful().then(process).end().then(assemble).terminate(SUCCEEDED).build());
        }
    }

    @Test void invalidTimersRefuseAndZeroTimerPreservesCarrier() {
        refusal("invalid timer", () -> define("timer").then(submit).waitFor("wait", Duration.ofNanos(-1))
                .terminate(SUCCEEDED).build());
        refusal("invalid timer", () -> define("timer").then(submit).waitFor("wait", null)
                .terminate(SUCCEEDED).build());
        var built = define("timer").then(submit).waitFor("wait", Duration.ZERO).terminate(SUCCEEDED).build();
        assertEquals(Set.of(JobHandle.class), built.successfulOutputs());
    }

    @Test void unresolvedChildAndReferencesRefuse() {
        refusal("unresolved child", () -> define("child").then(submit).subWorkflow("inspect", null)
                .terminate(SUCCEEDED).build());
        rawRefusal("unresolved reference", new Reference("ref", "root", "root", false), success());
        rawRefusal("foreign scope", new Reference("ref", "left", "right", true), success());
        rawRefusal("foreign scope", new Reference("ref", "parent", "child/private", true), success());
        rawRefusal("unresolved reference", new Reference("ref", "root", "future", false), success());
    }

    @Test void rawGraphUsesTheSameStructuralValidator() {
        rawRefusal("missing terminal", call("one"));
        rawRefusal("empty group", new Region("g", "static", 1, 1, true, List.of()), success());
        rawRefusal("empty region body", new Region("g", "loop", 1, 1, true, List.of(List.of())), success());
        rawRefusal("positive bounds", new Region("g", "loop", 0, 1, true, List.of(List.of(call("one")))), success());
        rawRefusal("settlement policy", new Region("g", "fan", 1, 1, false, List.of(List.of(call("one")))), success());
        rawRefusal("root terminal inside region", new Region("g", "static", 1, 1, true, List.of(List.of(success()))), success());
        rawRefusal("closed outcome domain", new Choice("route", CreditOutcome.class, Set.of(), List.of()), success());
        rawRefusal("arbitrary back edge", call("one"), new BackEdge("one"), success());
        rawRefusal("duplicate placement", call("same"), call("same"), success());
        rawRefusal("concrete operation contract", new Call("opaque", java.lang.Object.class, Reply.class), success());
    }

    @Test void staleAndAliasedHandlesRefuseAtBuild() {
        var original = define("stale").then(first);
        var next = original.then(second);
        original.then(third);
        refusal("stale builder handle", () -> next.then(fifth).terminate(SUCCEEDED).build());

        var root = define("open").then(createOrder);
        root.decision("unclosed", credit).when(RESERVED);
        refusal("stale builder handle", root::build);

        var bound = define("alias-bound").then(initial).repeatUntil("ready", ready);
        var body = bound.maxIterations(2);
        bound.maxIterations(3);
        refusal("stale builder handle", () -> body.then(revise).end().then(publish).terminate(SUCCEEDED).build());
    }

    @Test void unreachableWorkAndInconsistentOutputsRefuse() {
        refusal("unreachable authored work", () -> define("unreachable").then(createOrder).decision("credit", credit)
                .when(RESERVED).then(approve).terminate(SUCCEEDED).when(UNAVAILABLE).then(reject).terminate(SUCCEEDED)
                .end().then(first).terminate(SUCCEEDED).build());
        refusal("inconsistent successful output", () -> define("mismatch").then(createOrder).decision("credit", credit)
                .when(RESERVED).then(approve).terminate(SUCCEEDED).when(UNAVAILABLE).then(first).terminate(SUCCEEDED)
                .end().build());
        refusal("failure reason", () -> define("reason").then(first).terminate(FAILED).build());
    }

    @Test void repeatedOperationGetsDistinctPlacementsAndBuildFreezesCollections() {
        var terminal = define("placements").then(first).then(first).terminate(SUCCEEDED);
        var built = terminal.build();
        var calls = built.nodes().stream().filter(Call.class::isInstance).map(Call.class::cast).toList();
        assertEquals(2, calls.stream().map(Call::id).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> built.nodes().add(success()));
        assertThrows(IllegalStateException.class, terminal::build);
    }

    @Test void supportedChoiceInsideRegionHasRealGeneratedContinuation() {
        var built = define("choices").then(createOrder).parallel("checks").allSuccessful().branch("one")
                .decision("credit", credit).when(RESERVED).when(UNAVAILABLE).end()
                .end().then(approve).terminate(SUCCEEDED).build();
        assertEquals(Set.of(OrderReply.class), built.successfulOutputs());
    }

    @Test void directNestedRegionsRefuseExplicitly() {
        refusal("unsupported direct region nesting", () -> define("nested").then(initial)
                .repeatUntil("outer", ready).maxIterations(2)
                .parallel("inner").allSuccessful().branch("one").then(revise).end()
                .end().then(publish).terminate(SUCCEEDED).build());
    }

    @Test void rawMalformedDecisionRefusesDespiteBypassingFluentStages() {
        var arms = new ArrayList<Arm>();
        arms.add(new Arm(RESERVED, List.of(call("one"), success())));
        rawRefusal("missing or foreign outcomes", new Choice("route", CreditOutcome.class, Set.of(RESERVED, UNAVAILABLE), arms));
    }


    @Test void forgedChildCannotBypassRecursiveValidation() {
        var forged = new Definition<JobHandle>("forged", JobHandle.class, Set.of(JobStatus.class), List.of());
        refusal("missing terminal", () -> validateRaw(forged.name(), forged.input(), forged.nodes()));
        refusal("missing terminal", () -> define("parent").then(submit).subWorkflow("child", forged).then(report)
                .terminate(SUCCEEDED).build());
    }

    @Test void recursiveChildrenAndForgedOutputSummariesRefuseButReuseIsLegal() {
        List<Node> nodes = new ArrayList<>();
        var recursive = new Definition<JobHandle>("recursive", JobHandle.class, Set.of(JobStatus.class), nodes);
        nodes.add(new Child("self", recursive));
        nodes.add(success());
        refusal("recursive child definition", () -> define("parent").then(submit).subWorkflow("child", recursive)
                .terminate(SUCCEEDED).build());
        var forgedOutput = new Definition<JobHandle>("forged-output", JobHandle.class, Set.of(JobStatus.class), List.of(success()));
        refusal("child output summary mismatch", () -> define("parent").then(submit).subWorkflow("child", forgedOutput)
                .terminate(SUCCEEDED).build());
        var valid = define("inspect").then(inspect).terminate(SUCCEEDED).build();
        var parent = define("parent").then(submit).subWorkflow("first", valid).subWorkflow("second", valid)
                .then(report).terminate(SUCCEEDED).build();
        assertEquals(Set.of(JobReport.class), parent.successfulOutputs());
    }

    @Test void rawOutcomeDomainComesFromDeclaredEnumAndKindsMustBeKnown() {
        rawRefusal("closed outcome domain", new Choice("truncated", CreditOutcome.class, Set.of(RESERVED),
                List.of(new Arm(RESERVED, List.of(success())))));
        rawRefusal("closed outcome domain", new Choice("foreign-domain", CreditOutcome.class, Set.of(ForeignOutcome.RESERVED),
                List.of(new Arm(ForeignOutcome.RESERVED, List.of(success())))));
        rawRefusal("closed outcome domain", new Choice("not-enum", String.class, Set.of(RESERVED),
                List.of(new Arm(RESERVED, List.of(success())))));
        rawRefusal("unknown region kind", new Region("unknown", "not-a-kind", 1, 1, true, List.of(List.of(call("one")))), success());
        rawRefusal("unknown region kind", new Region("null-kind", null, 1, 1, true, List.of(List.of(call("one")))), success());
        rawRefusal("terminal outcome required", new End(null, "reason"));
        for (String kind : List.of("loop", "fan")) {
            rawRefusal("exactly one body required", new Region("multiple-bodies", kind, 1, 1, true,
                    List.of(List.of(call("one")), List.of(call("two")))), success());
            rawRefusal("exactly one body required", new Region("no-body", kind, 1, 1, true, List.of()), success());
        }
    }

    @Test void bindingAndGroupedCarrierAreDeliberateSeparateUnprovenBoundaries() {
        var incompatible = define("unbound").then(first).then(report).terminate(SUCCEEDED).build();
        assertEquals(Set.of(JobReport.class), incompatible.successfulOutputs());
        var group = define("product").then(split).parallel("g").allSuccessful()
                .branch("result").then(results).branch("log").then(logs).end().terminate(SUCCEEDED).build();
        assertEquals(split.output(), group.successfulOutputs().iterator().next());
    }
}
