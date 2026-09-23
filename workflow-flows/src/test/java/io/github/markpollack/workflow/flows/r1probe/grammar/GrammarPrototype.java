package io.github.markpollack.workflow.flows.r1probe.grammar;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import io.github.markpollack.judge.jury.interpretation.VerdictReading;

/** Test-only grammar experiment. No execution or persistence implementation. */
public final class GrammarPrototype {
    private GrammarPrototype() {}

    public enum Terminal { SUCCEEDED, FAILED }

    public abstract static class TypeRef<T> {
        public final Type type() {
            return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }

    public record Operation<I, O>(String name, Type input, Type output) {
        public static <I, O> Operation<I, O> named(String name, Class<I> input, Class<O> output) {
            return new Operation<>(name, input, output);
        }
        public static <I, O> Operation<I, O> named(String name, TypeRef<I> input, TypeRef<O> output) {
            return new Operation<>(name, input.type(), output.type());
        }
    }

    public record Assessment<I>(String name, Type input) {}
    public record Definition<I>(String name, Type input, Set<Type> successfulOutputs, List<Node> nodes) {}

    public sealed interface Node permits Call, End, Choice, Region, Timer, Child, Reference, BackEdge {}
    public record Call(String id, Type input, Type output) implements Node {}
    public record End(Terminal terminal, String reason) implements Node {}
    public record Arm(Enum<?> outcome, List<Node> nodes) {}
    public record Choice(String id, Type declaredDomain, Set<Enum<?>> outcomes, List<Arm> arms) implements Node {}
    public record Region(String id, String kind, int bound, int concurrency, boolean policy,
            List<List<Node>> members) implements Node {}
    public record Timer(String id, Duration duration) implements Node {}
    public record Child(String id, Definition<?> child) implements Node {}
    public record Reference(String id, String owner, String targetOwner, boolean resolved) implements Node {}
    public record BackEdge(String target) implements Node {}

    private static final class Session {
        final String name;
        final Type root;
        final List<Node> nodes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int nextId;
        boolean frozen;
        Session(String name, Type root) { this.name = name; this.root = root; }
        String placement(String label) { return label + "#" + nextId++; }
    }

    private abstract static class Handle {
        final Session session;
        boolean active = true;
        Handle(Session session) { this.session = session; }
        final void consume() {
            if (session.frozen) throw new IllegalStateException("definition already frozen");
            if (!active) session.errors.add("stale builder handle");
            active = false;
        }
    }

    public static Entry define(String name) { return new Entry(name); }
    public static <R> Root<R> define(String name, Class<R> root) {
        Session session = new Session(name, root);
        return new Root<>(session, session.nodes);
    }

    public static final class Entry {
        private final String name;
        private Entry(String name) { this.name = name; }
        public <I, O> Root<I> then(Operation<I, O> operation) {
            Session session = new Session(name, operation.input());
            Root<I> root = new Root<>(session, session.nodes);
            return root.then(operation);
        }
    }

    public abstract static class Sequence<S extends Sequence<S>> extends Handle {
        final List<Node> nodes;
        Sequence(Session session, List<Node> nodes) { super(session); this.nodes = nodes; }
        abstract S next();
        public <I, O> S then(Operation<I, O> operation) {
            consume();
            nodes.add(new Call(session.placement(operation.name()), operation.input(), operation.output()));
            return next();
        }
        public S waitFor(String id, Duration duration) {
            consume(); nodes.add(new Timer(session.placement(id), duration)); return next();
        }
        public S subWorkflow(String id, Definition<?> child) {
            consume(); nodes.add(new Child(session.placement(id), child)); return next();
        }
        public <I> LoopBound<S> repeatUntil(String id, Operation<I, Boolean> condition) {
            consume();
            return new LoopBound<>(session, nodes, id, condition, this::next);
        }
        public FanBound<S> forEach(String id) {
            consume(); return new FanBound<>(session, nodes, id, this::next);
        }
        public StaticPolicy<S> parallel(String id) {
            consume(); return new StaticPolicy<>(session, nodes, id, this::next);
        }
    }

    public static final class Root<R> extends Sequence<Root<R>> {
        Root(Session session, List<Node> nodes) { super(session, nodes); }
        @Override Root<R> next() { return new Root<>(session, nodes); }
        public <I, E extends Enum<E>> RootChoice<Root<R>> decision(String id, Operation<I, E> operation) {
            consume(); return rootChoice(session, nodes, id, operation.output(), this::next);
        }
        public <I> RootChoice<Root<R>> verdict(String id, Assessment<I> assessment) {
            consume(); return rootChoice(session, nodes, id, VerdictReading.class, this::next);
        }
        public Closed<R> terminate(Terminal terminal) { return terminate(terminal, ""); }
        public Closed<R> terminate(Terminal terminal, String reason) {
            consume(); nodes.add(new End(terminal, reason)); return new Closed<>(session);
        }
        public Definition<R> build() { consume(); return freeze(session); }
    }

    public static final class Closed<R> extends Handle {
        Closed(Session session) { super(session); }
        public Definition<R> build() { consume(); return freeze(session); }
    }

    private static Set<Enum<?>> domain(Type type) {
        if (type instanceof Class<?> raw && raw.isEnum()) {
            Set<Enum<?>> values = new HashSet<>();
            for (java.lang.Object value : raw.getEnumConstants()) values.add((Enum<?>) value);
            return values;
        }
        return Set.of();
    }

    private static <P> RootChoice<P> rootChoice(Session session, List<Node> nodes, String id,
            Type domain, Supplier<P> parent) {
        Choice choice = new Choice(session.placement(id), domain, domain(domain), new ArrayList<>());
        nodes.add(choice);
        return new RootChoice<>(session, choice, parent);
    }

    public static final class RootChoice<P> extends Handle {
        final Choice choice;
        final Supplier<P> parent;
        RootChoice(Session session, Choice choice, Supplier<P> parent) {
            super(session); this.choice = choice; this.parent = parent;
        }
        public RootArm<P> when(Enum<?> outcome) {
            consume(); List<Node> body = new ArrayList<>(); choice.arms().add(new Arm(outcome, body));
            return new RootArm<>(session, body, choice, parent);
        }
        public P end() { consume(); return parent.get(); }
    }

    public static final class RootArm<P> extends Sequence<RootArm<P>> {
        final Choice choice;
        final Supplier<P> parent;
        RootArm(Session session, List<Node> nodes, Choice choice, Supplier<P> parent) {
            super(session, nodes); this.choice = choice; this.parent = parent;
        }
        @Override RootArm<P> next() { return new RootArm<>(session, nodes, choice, parent); }
        public RootArm<P> when(Enum<?> outcome) {
            consume(); return new RootChoice<>(session, choice, parent).when(outcome);
        }
        public P end() { consume(); return parent.get(); }
        public RootChoice<P> terminate(Terminal terminal) { return terminate(terminal, ""); }
        public RootChoice<P> terminate(Terminal terminal, String reason) {
            consume(); nodes.add(new End(terminal, reason)); return new RootChoice<>(session, choice, parent);
        }
        public <I, E extends Enum<E>> RootChoice<RootArm<P>> decision(String id, Operation<I, E> operation) {
            consume(); return rootChoice(session, nodes, id, operation.output(), this::next);
        }
        public <I> RootChoice<RootArm<P>> verdict(String id, Assessment<I> assessment) {
            consume(); return rootChoice(session, nodes, id, VerdictReading.class, this::next);
        }
    }

    public abstract static class LocalSequence<S extends LocalSequence<S>> extends Sequence<S> {
        LocalSequence(Session session, List<Node> nodes) { super(session, nodes); }
        public <I, E extends Enum<E>> LocalChoice<S> decision(String id, Operation<I, E> operation) {
            consume(); return localChoice(session, nodes, id, operation.output(), this::next);
        }
        public <I> LocalChoice<S> verdict(String id, Assessment<I> assessment) {
            consume(); return localChoice(session, nodes, id, VerdictReading.class, this::next);
        }
    }

    private static <P> LocalChoice<P> localChoice(Session session, List<Node> nodes, String id,
            Type domain, Supplier<P> parent) {
        Choice choice = new Choice(session.placement(id), domain, domain(domain), new ArrayList<>());
        nodes.add(choice); return new LocalChoice<>(session, choice, parent);
    }

    public static final class LocalChoice<P> extends Handle {
        final Choice choice;
        final Supplier<P> parent;
        LocalChoice(Session session, Choice choice, Supplier<P> parent) {
            super(session); this.choice = choice; this.parent = parent;
        }
        public LocalArm<P> when(Enum<?> outcome) {
            consume(); List<Node> body = new ArrayList<>(); choice.arms().add(new Arm(outcome, body));
            return new LocalArm<>(session, body, choice, parent);
        }
        public P end() { consume(); return parent.get(); }
    }

    public static final class LocalArm<P> extends LocalSequence<LocalArm<P>> {
        final Choice choice;
        final Supplier<P> parent;
        LocalArm(Session session, List<Node> nodes, Choice choice, Supplier<P> parent) {
            super(session, nodes); this.choice = choice; this.parent = parent;
        }
        @Override LocalArm<P> next() { return new LocalArm<>(session, nodes, choice, parent); }
        public LocalArm<P> when(Enum<?> outcome) {
            consume(); return new LocalChoice<>(session, choice, parent).when(outcome);
        }
        public P end() { consume(); return parent.get(); }
    }

    public static final class Body<P> extends LocalSequence<Body<P>> {
        final Supplier<P> parent;
        Body(Session session, List<Node> nodes, Supplier<P> parent) { super(session, nodes); this.parent = parent; }
        @Override Body<P> next() { return new Body<>(session, nodes, parent); }
        public P end() { consume(); return parent.get(); }
    }

    public static final class LoopBound<P> extends Handle {
        final List<Node> nodes;
        final String id;
        final Operation<?, Boolean> condition;
        final Supplier<P> parent;
        LoopBound(Session session, List<Node> nodes, String id, Operation<?, Boolean> condition, Supplier<P> parent) {
            super(session); this.nodes = nodes; this.id = id; this.condition = condition; this.parent = parent;
        }
        public Body<P> maxIterations(int bound) {
            consume(); List<Node> body = new ArrayList<>();
            if (!Boolean.class.equals(condition.output())) session.errors.add("loop test must return Boolean");
            nodes.add(new Region(session.placement(id), "loop", bound, 1, true, List.of(body)));
            return new Body<>(session, body, parent);
        }
    }

    public static final class FanBound<P> extends Handle {
        final List<Node> nodes;
        final String id;
        final Supplier<P> parent;
        FanBound(Session session, List<Node> nodes, String id, Supplier<P> parent) {
            super(session); this.nodes = nodes; this.id = id; this.parent = parent;
        }
        public FanPolicy<P> maxItems(int bound) {
            consume(); return new FanPolicy<>(session, nodes, id, bound, 1, parent);
        }
    }

    public static final class FanPolicy<P> extends Handle {
        final List<Node> nodes;
        final String id;
        final int bound;
        final int concurrency;
        final Supplier<P> parent;
        FanPolicy(Session session, List<Node> nodes, String id, int bound, int concurrency, Supplier<P> parent) {
            super(session); this.nodes = nodes; this.id = id; this.bound = bound; this.concurrency = concurrency; this.parent = parent;
        }
        public FanReady<P> maxInFlight(int value) {
            consume(); return new FanReady<>(session, nodes, id, bound, value, parent);
        }
        public Body<P> allSuccessful() {
            consume(); return fanBody(session, nodes, id, bound, concurrency, parent);
        }
    }

    public static final class FanReady<P> extends Handle {
        final List<Node> nodes;
        final String id;
        final int bound;
        final int concurrency;
        final Supplier<P> parent;
        FanReady(Session session, List<Node> nodes, String id, int bound, int concurrency, Supplier<P> parent) {
            super(session); this.nodes = nodes; this.id = id; this.bound = bound; this.concurrency = concurrency; this.parent = parent;
        }
        public Body<P> allSuccessful() {
            consume(); return fanBody(session, nodes, id, bound, concurrency, parent);
        }
    }

    private static <P> Body<P> fanBody(Session session, List<Node> nodes, String id, int bound,
            int concurrency, Supplier<P> parent) {
        List<Node> body = new ArrayList<>();
        nodes.add(new Region(session.placement(id), "fan", bound, concurrency, true, List.of(body)));
        return new Body<>(session, body, parent);
    }

    public static final class StaticPolicy<P> extends Handle {
        final List<Node> nodes;
        final String id;
        final Supplier<P> parent;
        StaticPolicy(Session session, List<Node> nodes, String id, Supplier<P> parent) {
            super(session); this.nodes = nodes; this.id = id; this.parent = parent;
        }
        public Branches<P> allSuccessful() {
            consume();
            Region group = new Region(session.placement(id), "static", 1, 1, true, new ArrayList<>());
            nodes.add(group); return new Branches<>(session, group, parent);
        }
    }

    public static final class Branches<P> extends Handle {
        final Region group;
        final Supplier<P> parent;
        final Set<String> names;
        Branches(Session session, Region group, Supplier<P> parent) { this(session, group, parent, new HashSet<>()); }
        Branches(Session session, Region group, Supplier<P> parent, Set<String> names) {
            super(session); this.group = group; this.parent = parent; this.names = names;
        }
        public Member<P> branch(String name) {
            consume(); if (!names.add(name)) session.errors.add("duplicate branch " + name);
            List<Node> member = new ArrayList<>(); group.members().add(member);
            return new Member<>(session, member, group, parent, names);
        }
    }

    public static final class Member<P> extends LocalSequence<Member<P>> {
        final Region group;
        final Supplier<P> parent;
        final Set<String> names;
        Member(Session session, List<Node> nodes, Region group, Supplier<P> parent, Set<String> names) {
            super(session, nodes); this.group = group; this.parent = parent; this.names = names;
        }
        @Override Member<P> next() { return new Member<>(session, nodes, group, parent, names); }
        public Member<P> branch(String name) {
            consume(); return new Branches<>(session, group, parent, names).branch(name);
        }
        public P end() { consume(); return parent.get(); }
    }

    private static <R> Definition<R> freeze(Session session) {
        if (!session.errors.isEmpty()) throw new IllegalArgumentException(session.name + ": " + session.errors);
        Validation validation = validateRaw(session.name, session.root, session.nodes);
        session.frozen = true;
        return new Definition<>(session.name, session.root, validation.outputs(), immutable(session.nodes));
    }

    private static List<Node> immutable(List<Node> nodes) {
        return nodes.stream().map(node -> switch (node) {
            case Choice c -> new Choice(c.id(), c.declaredDomain(), Set.copyOf(c.outcomes()), c.arms().stream()
                    .map(arm -> new Arm(arm.outcome(), immutable(arm.nodes()))).toList());
            case Region r -> new Region(r.id(), r.kind(), r.bound(), r.concurrency(), r.policy(),
                    r.members().stream().map(GrammarPrototype::immutable).toList());
            case Child c -> new Child(c.id(), copyChild(c.child()));
            default -> node;
        }).toList();
    }

    private static <R> Definition<R> copyChild(Definition<R> child) {
        return new Definition<>(child.name(), child.input(), Set.copyOf(child.successfulOutputs()), immutable(child.nodes()));
    }

    public record Validation(Set<Type> outputs) {}

    public static Validation validateRaw(String name, Type input, List<Node> nodes) {
        return validateTree(name, input, nodes, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Validation validateTree(String name, Type input, List<Node> nodes, Set<Definition<?>> activeChildren) {
        List<String> errors = new ArrayList<>();
        Set<Type> outputs = new HashSet<>();
        Set<String> placements = new HashSet<>();
        if (input == null || input == java.lang.Object.class) errors.add("concrete root required");
        inspect(nodes, false, false, input == null ? Set.of() : Set.of(input), outputs, errors, placements, activeChildren);
        if (outputs.size() > 1) errors.add("inconsistent successful output contracts " + outputs);
        if (!errors.isEmpty()) throw new IllegalArgumentException(name + ": " + errors);
        return new Validation(Set.copyOf(outputs));
    }

    private static Set<Type> inspect(List<Node> nodes, boolean continuation, boolean regionBody,
            Set<Type> incoming, Set<Type> outputs, List<String> errors, Set<String> placements, Set<Definition<?>> activeChildren) {
        Set<Type> current = incoming;
        boolean live = true;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (!live) { errors.add("unreachable authored work"); break; }
            String id = switch (node) {
                case Call c -> c.id(); case Choice c -> c.id(); case Region r -> r.id();
                case Timer t -> t.id(); case Child c -> c.id(); case Reference r -> r.id();
                default -> null;
            };
            if (id != null && !placements.add(id)) errors.add("duplicate placement " + id);
            boolean follows = i + 1 < nodes.size() || continuation;
            switch (node) {
                case Call c -> {
                    if (c.input() == null || c.output() == null || c.input() == java.lang.Object.class
                            || c.output() == java.lang.Object.class) errors.add("concrete operation contract " + c.id());
                    else current = Set.of(c.output());
                }
                case End end -> {
                    if (regionBody) errors.add("root terminal inside region");
                    if (end.terminal() == null) errors.add("terminal outcome required");
                    else if (end.terminal() == Terminal.SUCCEEDED) outputs.addAll(current);
                    else if (end.reason() == null || end.reason().isBlank()) errors.add("failure reason required");
                    live = false;
                }
                case Choice choice -> {
                    Set<Enum<?>> seen = new HashSet<>();
                    Set<Type> continuing = new HashSet<>();
                    Set<Enum<?>> declared = domain(choice.declaredDomain());
                    if (declared.isEmpty() || !declared.equals(choice.outcomes())) errors.add("closed outcome domain required " + choice.id());
                    for (Arm arm : choice.arms()) {
                        if (arm.outcome() == null || !choice.outcomes().contains(arm.outcome())) errors.add("foreign outcome " + arm.outcome());
                        if (!seen.add(arm.outcome())) errors.add("duplicate outcome " + arm.outcome());
                        continuing.addAll(inspect(arm.nodes(), follows, regionBody, current, outputs, errors, placements, activeChildren));
                    }
                    if (!seen.equals(choice.outcomes())) errors.add("missing or foreign outcomes " + choice.id());
                    current = continuing;
                    live = !continuing.isEmpty();
                }
                case Region region -> {
                    if (regionBody) errors.add("unsupported direct region nesting " + region.id());
                    if (!"loop".equals(region.kind()) && !"fan".equals(region.kind()) && !"static".equals(region.kind())) errors.add("unknown region kind " + region.id());
                    if (region.bound() <= 0 || region.concurrency() <= 0) errors.add("positive bounds required " + region.id());
                    if (!region.policy()) errors.add("settlement policy required " + region.id());
                    if (region.members().isEmpty()) errors.add("empty group " + region.id());
                    if (("loop".equals(region.kind()) || "fan".equals(region.kind())) && region.members().size() != 1) errors.add("exactly one body required " + region.id());
                    for (List<Node> member : region.members()) {
                        if (member.isEmpty()) errors.add("empty region body " + region.id());
                        inspect(member, true, true, current, outputs, errors, placements, activeChildren);
                    }
                    // Group data binding is tested separately; this parser does not infer products.
                }
                case Timer timer -> {
                    if (timer.duration() == null || timer.duration().isNegative()) errors.add("invalid timer " + timer.id());
                }
                case Child child -> {
                    if (child.child() == null) errors.add("unresolved child " + child.id());
                    else if (!activeChildren.add(child.child())) errors.add("recursive child definition " + child.id());
                    else {
                        try {
                            Validation verified = validateTree(child.child().name(), child.child().input(), child.child().nodes(), activeChildren);
                            if (!verified.outputs().equals(child.child().successfulOutputs())) errors.add("child output summary mismatch " + child.id());
                            current = verified.outputs();
                        }
                        finally { activeChildren.remove(child.child()); }
                    }
                }
                case Reference reference -> {
                    if (!reference.resolved()) errors.add("unresolved reference " + reference.id());
                    if (!reference.owner().equals(reference.targetOwner())) errors.add("foreign scope " + reference.id());
                }
                case BackEdge edge -> errors.add("arbitrary back edge " + edge.target());
            }
        }
        if (live && !continuation) errors.add("missing terminal or dangling path");
        return live ? current : Set.of();
    }
}
