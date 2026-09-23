package io.github.markpollack.workflow.flows.r1probe.binding;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.markpollack.workflow.flows.r1probe.binding.TypedPlan.*;

class BindingFalsifiersTest {

    record Request(List<String> labels) {}
    record First(String text) {}
    record SecondInput(First first, Request original) {}
    record Second(String text) {}
    record Fourth(String text) {}
    record FifthInput(Fourth latest, SecondInput earlier) {}
    record Final(String text) {}
    record NeedFirst(First first) {}
    record Recursive(NeedFirst nested) {}
    record PairOfFirsts(First source, First target) {}
    record SourceRole(First value) {}
    record TargetRole(First value) {}
    record RolePair(SourceRole source, TargetRole target) {}
    record Mixed(First first, Second second) {}
    record Lists(List<First> first, List<Second> second) {}

    @Test void wholeInputsAreAliasesRatherThanAdditionalCandidates() {
        Scope s = new Scope("alias", Request.class);
        Dispatch first = s.call("first", Request.class, First.class);
        Dispatch second = s.call("second", Request.class, Second.class);
        Dispatch last = s.call("last", Request.class, Final.class);
        assertThat(first.input()).isSameAs(second.input()).isSameAs(last.input());
        assertThat(s.candidates(Request.class)).hasSize(1);
        assertThat(s.facts).noneMatch(f -> f.id().endsWith(".in"));
    }

    @Test void laterComponentsCannotReassembleAnAlreadyCapturedInput() throws Exception {
        Scope s = new Scope("saved", Request.class);
        Dispatch first = s.call("first", Request.class, First.class);
        Dispatch second = s.call("second", SecondInput.class, Second.class);
        Dispatch laterFirst = s.call("laterFirst", Second.class, First.class);
        Dispatch laterRequest = s.call("laterRequest", First.class, Request.class);
        Dispatch fourth = s.call("fourth", Request.class, Fourth.class);
        Dispatch fifth = s.call("fifth", FifthInput.class, Final.class);
        assertThat(second.mapping()).isEqualTo("SecondInput(first.out,root)");
        assertThat(fifth.mapping()).isEqualTo("FifthInput(fourth.out,second.in)");
        assertThat(fifth.input().parts().get(1).identity()).isEqualTo("saved/second.in");

        SnapshotTable table = new SnapshotTable();
        ArrayList<String> mutable = new ArrayList<>(List.of("original"));
        table.save(first.input(), new Request(mutable));
        table.save(first.output(), new First("first-version"));
        SecondInput atDispatch = (SecondInput) table.materialize(second.input());
        assertThat(atDispatch).isEqualTo(new SecondInput(new First("first-version"), new Request(List.of("original"))));
        mutable.set(0, "mutated-after-dispatch");
        table.save(laterFirst.output(), new First("newer-version"));
        table.save(laterRequest.output(), new Request(List.of("newer-request")));
        table.save(fourth.output(), new Fourth("fourth"));
        SnapshotTable restored = table.reopen();
        FifthInput actual = (FifthInput) restored.materialize(fifth.input());
        assertThat(actual.earlier()).isEqualTo(atDispatch);
        assertThat(actual.latest()).isEqualTo(new Fourth("fourth"));
        assertThat(restored.materialize(second.input())).isEqualTo(atDispatch);
        assertThatThrownBy(() -> restored.save(second.input(), new SecondInput(new First("wrong"), new Request(List.of()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("immutable");

        Fact rebuilt = new Fact("second.in", SecondInput.class, List.of(laterFirst.output(), laterRequest.output()), "mutant/second.in");
        Fact wrong = new Fact("fifth.in", FifthInput.class, List.of(fourth.output(), rebuilt), "mutant/fifth.in");
        assertThatThrownBy(() -> assertThat(wrong.parts().get(1).identity()).isEqualTo("saved/second.in"))
                .isInstanceOf(AssertionError.class);
        assertThat(((FifthInput) restored.materialize(wrong)).earlier()).isNotEqualTo(atDispatch);
    }

    @Test void equalPayloadsDoNotMakeDifferentProducersAliases() throws Exception {
        Scope s = new Scope("equal", Request.class);
        Dispatch a = s.call("a", Request.class, First.class);
        Dispatch b = s.call("b", Request.class, First.class);
        SnapshotTable values = new SnapshotTable();
        values.save(a.output(), new First("equal"));
        values.save(b.output(), new First("equal"));
        assertThat(values.materialize(a.output())).isEqualTo(values.materialize(b.output()));
        assertThat(a.output().identity()).isNotEqualTo(b.output().identity());
        assertThatThrownBy(() -> assertThat(b.output().identity()).isEqualTo("equal/a.out"))
                .isInstanceOf(AssertionError.class);
        s.call("cleanup", Request.class, Fourth.class);
        assertThatThrownBy(() -> s.call("consumer", First.class, Final.class)).isInstanceOf(Refusal.class)
                .hasMessageContaining("ambiguous").hasMessageContaining("a.out").hasMessageContaining("b.out");
    }

    @Test void currentCarrierHasStructuralPrecedenceButEarlierProducersHaveNoNearestRule() {
        Scope s = new Scope("carrier", Request.class);
        s.call("older", Request.class, First.class);
        s.call("newer", Request.class, First.class);
        assertThat(s.resolve(First.class, "direct", false).id()).isEqualTo("newer.out");
        s.call("other", Request.class, Fourth.class);
        assertThatThrownBy(() -> s.resolve(First.class, "later", false)).isInstanceOf(Refusal.class).hasMessageContaining("ambiguous");
    }

    @Test void assemblyIsOnlyOneLevelAndComponentNamesNeverChooseProducers() {
        Scope s = new Scope("assembly", Request.class);
        s.call("source", Request.class, First.class);
        assertThatThrownBy(() -> s.call("recursive", Recursive.class, Final.class)).isInstanceOf(Refusal.class).hasMessageContaining("NeedFirst");
        s.call("target", Request.class, First.class);
        s.call("cleanup", Request.class, Fourth.class);
        assertThatThrownBy(() -> s.call("pair", PairOfFirsts.class, Final.class)).isInstanceOf(Refusal.class).hasMessageContaining("ambiguous");
    }

    @Test void missingArmCannotBorrowAnInputAssembledOnlyOnTheOtherArm() {
        Scope s = new Scope("missing", Request.class);
        s.call("first", Request.class, First.class);
        Scope assigned = s.fork("assigned"), missing = s.fork("missing");
        assigned.call("second", SecondInput.class, Second.class);
        s.merge("choice", assigned, missing);
        assertThatThrownBy(() -> s.call("consumer", SecondInput.class, Final.class))
                .isInstanceOf(Refusal.class).hasMessageContaining("missing assignment").hasMessageContaining("missing/missing");
    }

    @Test void terminatedArmContributesNoRequiredAssignment() {
        Scope s = new Scope("terminated", Request.class);
        Scope live = s.fork("live"), stopped = s.fork("stopped");
        live.call("produce", Request.class, First.class);
        stopped.terminated = true;
        s.merge("choice", live, stopped);
        assertThat(s.call("consumer", First.class, Final.class).mapping()).isEqualTo("produce.out");
    }

    @Test void sameAuthoredPlacementInDifferentArmsRemainsTwoOrigins() {
        Scope s = new Scope("branches", Request.class);
        Scope left = s.fork("left"), right = s.fork("right");
        left.call("produce", Request.class, First.class);
        right.call("produce", Request.class, First.class);
        s.merge("choice", left, right);
        Capture capture = s.captures.getFirst();
        assertThat(capture.arms()).extracting(Fact::identity).containsExactly("branches/left/produce.out", "branches/right/produce.out");
        assertThat(s.call("consume", First.class, Final.class).mapping()).isEqualTo("choice.capture<First>");
    }

    @Test void samePlacementAcrossIterationsHasDerivedDistinctSnapshotCoordinates() {
        Scope s = new Scope("iterations", Request.class);
        List<Fact> saved = new ArrayList<>();
        for (int iteration = 1; iteration <= 3; iteration++) {
            Scope body = s.iteration("repeat", iteration);
            body.call("first", Request.class, First.class);
            Dispatch second = body.call("second", SecondInput.class, Second.class);
            body.call("fourth", Second.class, Fourth.class);
            Dispatch fifth = body.call("fifth", FifthInput.class, Final.class);
            assertThat(fifth.input().parts().get(1)).isSameAs(second.input());
            saved.add(second.input());
            s.carry("repeat", body);
        }
        assertThat(saved).extracting(Fact::identity).containsExactly("iterations/repeat[1]/second.in", "iterations/repeat[2]/second.in", "iterations/repeat[3]/second.in");
        assertThat(saved).doesNotHaveDuplicates();
        assertThatThrownBy(() -> s.call("historical", SecondInput.class, Final.class)).isInstanceOf(Refusal.class);
    }

    @Test void siblingAndChildInternalsNeverEnterAncestorCandidates() {
        Scope parent = new Scope("parent", Request.class);
        Scope left = parent.fork("left"), right = parent.fork("right");
        left.call("private", Request.class, First.class);
        assertThatThrownBy(() -> right.call("leak", First.class, Final.class)).isInstanceOf(Refusal.class);
        assertThatThrownBy(() -> parent.call("leak", First.class, Final.class)).isInstanceOf(Refusal.class);
        Scope child = Scope.child("child", Request.class);
        child.call("private", Request.class, Second.class);
        parent.call("childCall", Request.class, Fourth.class);
        assertThatThrownBy(() -> parent.call("leakChild", Second.class, Final.class)).isInstanceOf(Refusal.class);
        assertThatThrownBy(() -> child.call("leakParent", Fourth.class, Final.class)).isInstanceOf(Refusal.class);
    }

    @Test void completedProductExposesOnlyMemberResultsNotPrivateInputs() {
        Scope s = new Scope("join", Request.class);
        Scope a = s.fork("a"), b = s.fork("b");
        a.call("first", Request.class, First.class);
        a.call("assembled", SecondInput.class, Second.class);
        b.call("fourth", Request.class, Fourth.class);
        s.join("product", a, b);
        assertThatThrownBy(() -> s.resolve(SecondInput.class, "private", false)).isInstanceOf(Refusal.class);
        assertThat(s.resolve(Second.class, "settled", false).id()).isEqualTo("assembled.out");
    }

    @Test void genericElementTypesRemainDistinctAndRawOrObjectContractsRefuse() {
        Type firsts = new TypeRef<List<First>>() {}.type;
        Type seconds = new TypeRef<List<Second>>() {}.type;
        Scope s = new Scope("generics", Request.class);
        s.call("firsts", Request.class, firsts);
        s.call("seconds", Request.class, seconds);
        Dispatch lists = s.call("consumer", Lists.class, Final.class);
        assertThat(lists.mapping()).isEqualTo("Lists(firsts.out,seconds.out)");
        assertThat(lists.input().parts()).extracting(Fact::type).containsExactly(firsts, seconds);
        assertThatThrownBy(() -> new Scope("raw", List.class)).isInstanceOf(Refusal.class);
        assertThatThrownBy(() -> new Scope("object", Object.class)).isInstanceOf(Refusal.class);
        assertThatThrownBy(() -> new Scope("objects", new TypeRef<List<Object>>() {}.type)).isInstanceOf(Refusal.class);
        assertThatThrownBy(() -> new Scope("wildcard", new TypeRef<List<? extends First>>() {}.type)).isInstanceOf(Refusal.class);
        Scope wrong = new Scope("wrong", firsts);
        assertThatThrownBy(() -> wrong.call("consumer", seconds, Final.class)).isInstanceOf(Refusal.class);
    }

    @Test void homogeneousResultsFollowDeclarationDespiteReverseArrival() throws Exception {
        Scope s = new Scope("ordered", Request.class);
        Scope a = s.fork("a"), b = s.fork("b");
        Dispatch first = a.call("translate", Request.class, First.class);
        Dispatch second = b.call("translate", Request.class, First.class);
        Fact aggregate = s.join("results", a, b);
        SnapshotTable table = new SnapshotTable();
        table.save(second.output(), new First("second"));
        table.save(first.output(), new First("first"));
        assertThat(aggregate.parts()).extracting(Fact::identity).containsExactly("ordered/a/translate.out", "ordered/b/translate.out");
        assertThat(table.materialize(aggregate)).isEqualTo(List.of(new First("first"), new First("second")));
    }

    @Test void heterogeneousDomainRecordsUseDeclaredTypesUnderReverseArrival() throws Exception {
        Scope s = new Scope("mixed", Request.class);
        Scope a = s.fork("a"), b = s.fork("b");
        Dispatch first = a.call("work", Request.class, First.class);
        Dispatch second = b.call("work", Request.class, Second.class);
        s.join("product", a, b);
        Dispatch consume = s.call("consume", Mixed.class, Final.class);
        assertThat(consume.input().parts()).extracting(Fact::identity).containsExactly("mixed/a/work.out", "mixed/b/work.out");
        SnapshotTable table = new SnapshotTable();
        table.save(second.output(), new Second("second"));
        table.save(first.output(), new First("first"));
        assertThat(table.materialize(consume.input())).isEqualTo(new Mixed(new First("first"), new Second("second")));
    }

    @Test void meaningfulRoleWrappersWorkButTwoUnwrappedRolesAreNotGuessed() {
        Scope roles = new Scope("roles", Request.class);
        Scope source = roles.fork("source"), target = roles.fork("target");
        source.call("read", Request.class, SourceRole.class);
        target.call("read", Request.class, TargetRole.class);
        roles.join("roles", source, target);
        Dispatch input = roles.call("consume", RolePair.class, Final.class);
        assertThat(input.input().parts()).extracting(Fact::identity).containsExactly("roles/source/read.out", "roles/target/read.out");
        Scope ambiguous = new Scope("ambiguous", Request.class);
        Scope a = ambiguous.fork("source"), b = ambiguous.fork("target");
        a.call("read", Request.class, First.class);
        b.call("read", Request.class, First.class);
        ambiguous.join("roles", a, b);
        assertThatThrownBy(() -> ambiguous.call("consume", PairOfFirsts.class, Final.class)).isInstanceOf(Refusal.class);
    }

    @Test void twoIndependentOffCarrierCaptureRolesRefuseRatherThanUseVisitOrder() {
        Scope s = new Scope("competing", Request.class);
        Scope a = s.fork("a"), b = s.fork("b");
        a.call("one", Request.class, First.class);
        a.call("two", Request.class, First.class);
        a.call("cleanup", Request.class, Fourth.class);
        b.call("one", Request.class, First.class);
        b.call("cleanup", Request.class, Fourth.class);
        assertThatThrownBy(() -> s.merge("choice", a, b)).isInstanceOf(Refusal.class).hasMessageContaining("competing capture roles");
    }

    @Test void carriedStateIsProvenanceLinkedAndIndependentSameTypeIsNotRewritten() {
        Scope s = new Scope("lineage", First.class);
        Dispatch next = s.call("transform", First.class, First.class);
        assertThat(next.input().identity()).isEqualTo("lineage/root");
        s.call("cleanup", First.class, Fourth.class);
        assertThat(s.resolve(First.class, "later", false).identity()).isEqualTo("lineage/transform.out");
        assertThat(s.superseded).extracting(Fact::identity).containsExactly("lineage/root");
    }

    @Test void cleanupInsideAnArmDoesNotProveAnIndependentReplacementRole() {
        Scope s = new Scope("unproven", Request.class);
        s.call("original", Request.class, First.class);
        Scope change = s.fork("change"), skip = s.fork("skip");
        change.call("independent", Request.class, First.class);
        change.call("cleanup", Request.class, Fourth.class);
        skip.call("cleanup", Request.class, Fourth.class);
        s.merge("choice", change, skip);
        assertThatThrownBy(() -> s.call("consume", First.class, Final.class)).isInstanceOf(Refusal.class)
                .hasMessageContaining("unproven capture role")
                .hasMessageContaining("unproven/change/independent.out")
                .hasMessageContaining("unproven/original.out");
    }

    @Test void aNewExclusiveCarrierDoesNotEraseAnIndependentEarlierRole() {
        Scope s = new Scope("independent", Request.class);
        s.call("original", Request.class, First.class);
        s.call("before", Request.class, Fourth.class);
        Scope a = s.fork("a"), b = s.fork("b");
        a.call("new", Request.class, First.class);
        b.call("new", Request.class, First.class);
        s.merge("choice", a, b);
        assertThat(s.resolve(First.class, "immediate", false).id()).isEqualTo("choice.capture<First>");
        assertThat(s.superseded).noneMatch(fact -> fact.id().equals("original.out"));
        s.call("cleanup", Request.class, Fourth.class);
        assertThatThrownBy(() -> s.resolve(First.class, "later", false)).isInstanceOf(Refusal.class)
                .hasMessageContaining("ambiguous").hasMessageContaining("original.out").hasMessageContaining("choice.capture<First>");
    }

    @Test void structuredCoordinatesCannotAliasDelimiterBearingNames() {
        Scope s = new Scope("root", Request.class);
        Fact flat = s.fork("left/right").call("work", Request.class, First.class).output();
        Fact nested = s.fork("left").fork("right").call("work", Request.class, First.class).output();
        Fact authoredIndex = s.fork("repeat[1]").call("work", Request.class, First.class).output();
        Fact structuredIndex = s.iteration("repeat", 1).call("work", Request.class, First.class).output();
        assertThat(flat.identity()).isEqualTo("root/left%2Fright/work.out");
        assertThat(nested.identity()).isEqualTo("root/left/right/work.out");
        assertThat(authoredIndex.identity()).isEqualTo("root/repeat%5B1%5D/work.out");
        assertThat(structuredIndex.identity()).isEqualTo("root/repeat[1]/work.out");
        assertThat(List.of(flat, nested, authoredIndex, structuredIndex)).doesNotHaveDuplicates();
        Fact literalEscape = s.fork("left%2Fright").call("work", Request.class, First.class).output();
        assertThat(literalEscape.identity()).isNotEqualTo(flat.identity());
    }

    @Test void loopResultDoesNotEraseAnIndependentEarlierRole() {
        Scope s = new Scope("loopRole", Request.class);
        s.call("original", Request.class, First.class);
        s.call("before", Request.class, Fourth.class);
        Scope iteration = s.iteration("repeat", 1);
        iteration.call("new", Request.class, First.class);
        s.carry("repeat", iteration);
        assertThat(s.resolve(First.class, "immediate", false).id()).isEqualTo("repeat.result");
        s.call("cleanup", Request.class, Fourth.class);
        assertThatThrownBy(() -> s.resolve(First.class, "later", false)).isInstanceOf(Refusal.class)
                .hasMessageContaining("ambiguous").hasMessageContaining("original.out").hasMessageContaining("repeat.result");
    }

    @Test void unconditionalProducerRetiresEarlierPartialAssignmentMarker() {
        Scope s = new Scope("definite", Request.class);
        Scope a = s.fork("a"), b = s.fork("b");
        a.call("partial", Request.class, First.class);
        s.merge("choice", a, b);
        assertThat(s.missing).containsKey(First.class);
        s.call("definite", Request.class, First.class);
        s.call("cleanup", Request.class, Fourth.class);
        assertThat(s.resolve(First.class, "consume", false).identity()).isEqualTo("definite/definite.out");
        assertThat(s.missing).doesNotContainKey(First.class);
    }

    @Test void unconditionalProducerRetiresUnprovenPrivateAlternativesWithoutErasingOuterValues() {
        Scope s = new Scope("newRole", Request.class);
        Scope a = s.fork("a"), b = s.fork("b");
        a.call("value", Request.class, First.class);
        a.call("cleanup", Request.class, Fourth.class);
        b.call("value", Request.class, First.class);
        b.call("cleanup", Request.class, Fourth.class);
        s.merge("choice", a, b);
        assertThat(s.unproven).containsKey(First.class);
        s.call("definite", Request.class, First.class);
        s.call("cleanup", Request.class, Fourth.class);
        assertThat(s.resolve(First.class, "consume", false).identity()).isEqualTo("newRole/definite.out");
        assertThat(s.unproven).doesNotContainKey(First.class);
    }

    /** Bytes are copied between instances only to test exact input retention; this is not a store. */
    static final class SnapshotTable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, byte[]> bytes = new LinkedHashMap<>();

        void save(Fact fact, Object value) throws Exception {
            byte[] encoded = mapper.writeValueAsBytes(value);
            byte[] previous = bytes.putIfAbsent(fact.identity(), encoded);
            if (previous != null && !Arrays.equals(previous, encoded)) throw new IllegalStateException("immutable input " + fact.identity());
        }

        Object materialize(Fact fact) throws Exception {
            byte[] saved = bytes.get(fact.identity());
            if (saved == null) {
                if (fact.parts().isEmpty()) throw new IllegalStateException("Missing fact " + fact.identity());
                List<Object> values = new ArrayList<>();
                for (Fact part : fact.parts()) values.add(materialize(part));
                Object value;
                if (fact.type() instanceof ParameterizedType p && p.getRawType() == List.class) value = List.copyOf(values);
                else {
                    Class<?> record = (Class<?>) fact.type();
                    Class<?>[] arguments = Arrays.stream(record.getRecordComponents()).map(component -> component.getType()).toArray(Class<?>[]::new);
                    value = record.getDeclaredConstructor(arguments).newInstance(values.toArray());
                }
                save(fact, value);
                saved = bytes.get(fact.identity());
            }
            return mapper.readValue(saved, mapper.getTypeFactory().constructType(fact.type()));
        }

        SnapshotTable reopen() {
            SnapshotTable reopened = new SnapshotTable();
            bytes.forEach((key, value) -> reopened.bytes.put(key, value.clone()));
            return reopened;
        }
    }
}
