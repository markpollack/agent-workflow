package io.github.markpollack.workflow.flows.r1probe.integrated;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import io.github.markpollack.judge.jury.interpretation.VerdictReading;

import static io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedModel.*;

/** Implemented test-only staged front end. All complete-definition checks use IntegratedCompiler. */
public final class FluentCandidate {
    private FluentCandidate() {}
    public static final class Success { private Success() {} }
    public static final class Failure { private Failure() {} }
    public static final Success SUCCEEDED = new Success();
    public static final Failure FAILED = new Failure();

    private static final class Session {
        final String name;
        final Type input;
        final List<Supplier<Node>> root = new ArrayList<>();
        int sequence;
        boolean built;
        Session(String name, Type input) { this.name = name; this.input = input; }
        String id(String label) { return sequence++ + ":" + label; }
    }

    private abstract static class Handle {
        final Session session;
        private boolean live = true;
        Handle(Session session) { this.session = session; }
        final void use() {
            if (!live || session.built) throw new IllegalStateException("stale or closed builder handle");
            live = false;
        }
    }

    private static List<Node> lower(List<Supplier<Node>> nodes) { return nodes.stream().map(Supplier::get).toList(); }
    private static void call(Session session, List<Supplier<Node>> nodes, Op<?,?> op) {
        String id = session.id(op.name()); nodes.add(() -> new Call(id, op));
    }
    private static void terminal(List<Supplier<Node>> nodes, Terminal outcome, String reason) {
        nodes.add(() -> new End(outcome, reason));
    }
    private static void timer(Session session, List<Supplier<Node>> nodes, String label, Duration duration) {
        String id = session.id(label); nodes.add(() -> new Timer(id, duration));
    }
    private static void child(Session session, List<Supplier<Node>> nodes, String label, Built<?,?> child) {
        String id = session.id(label); nodes.add(() -> new Child(id, child.definition()));
    }
    @SuppressWarnings("unchecked")
    private static <I,O> Built<I,O> build(Session session, Type output) {
        Definition<I,O> definition = new Definition<>(session.name, session.input, output, lower(session.root));
        Built<I,O> result = (Built<I,O>) IntegratedCompiler.build(definition);
        session.built = true;
        return result;
    }

    public static Entry define(String name) { return new Entry(name); }
    public static <I> Seq<I,I> define(String name, Class<I> input) {
        Session session = new Session(name, input); return new Seq<>(session, input);
    }
    public static <I> Seq<I,I> define(String name, TypeRef<I> input) {
        Session session = new Session(name, input.type()); return new Seq<>(session, input.type());
    }
    public static final class Entry {
        private final String name;
        private Entry(String name) { this.name = name; }
        public <I,O> Seq<I,O> then(Op<I,O> op) {
            Session session = new Session(name, op.input()); call(session, session.root, op);
            return new Seq<>(session, op.output());
        }
    }

    public static final class Seq<I,C> extends Handle {
        final Type current;
        Seq(Session session, Type current) { super(session); this.current = current; }
        public <N,O> Seq<I,O> then(Op<N,O> op) {
            use(); call(session, session.root, op); return new Seq<>(session, op.output());
        }
        public Seq<I,C> waitFor(String label, Duration duration) {
            use(); timer(session, session.root, label, duration); return new Seq<>(session, current);
        }
        public <N,O> Seq<I,O> subWorkflow(String label, Built<N,O> child) {
            use(); child(session, session.root, label, child); return new Seq<>(session, child.output());
        }
        public <N,E extends Enum<E>> FirstChoice<I,C,E> decision(String label, Op<N,E> op) {
            use(); return new FirstChoice<>(session, choice(session, session.root, label, op, null), current);
        }
        public <N> FirstChoice<I,C,VerdictReading> verdict(String label, Assessment<N> assessment) {
            use(); return new FirstChoice<>(session, choice(session, session.root, label, null, assessment), current);
        }
        public <N> LoopBound<I,C> repeatUntil(String label, Op<N,Boolean> test) {
            use(); LoopDraft loop = new LoopDraft(session.id(label), test);
            session.root.add(loop::lower); return new LoopBound<>(session, loop, current);
        }
        public FanBound<I> forEach(String label) {
            use(); Type element = current instanceof ParameterizedType p && p.getRawType() == List.class
                    ? p.getActualTypeArguments()[0] : null;
            FanDraft fan = new FanDraft(session.id(label), element);
            session.root.add(fan::lower); return new FanBound<>(session, fan);
        }
        public ParallelPolicy<GroupContinuation<I>> parallel(String label) {
            use(); return parallelDraft(session, session.root, label, null, () -> new GroupContinuation<>(session));
        }
        public <G> ParallelPolicy<Seq<I,G>> parallel(String label, Class<G> output) {
            use(); return parallelDraft(session, session.root, label, output, () -> new Seq<>(session, output));
        }
        public <G> ParallelPolicy<Seq<I,G>> parallel(String label, TypeRef<G> output) {
            use(); return parallelDraft(session, session.root, label, output.type(), () -> new Seq<>(session, output.type()));
        }
        public Closed<I,C> terminate(Success intent) {
            Objects.requireNonNull(intent); use(); terminal(session.root, Terminal.SUCCEEDED, ""); return new Closed<>(session, current);
        }
        public Closed<I,C> terminate(Failure intent, String reason) {
            Objects.requireNonNull(intent); use(); terminal(session.root, Terminal.FAILED, reason); return new Closed<>(session, current);
        }
        public Built<I,C> build() { use(); return FluentCandidate.build(session, current); }
    }

    public static final class Closed<I,O> extends Handle {
        final Type output;
        Closed(Session session, Type output) { super(session); this.output = output; }
        public Built<I,O> build() { use(); return FluentCandidate.build(session, output); }
    }

    public static final class GroupContinuation<I> extends Handle {
        GroupContinuation(Session session) { super(session); }
        public <N,O> Seq<I,O> then(Op<N,O> op) {
            use(); call(session, session.root, op); return new Seq<>(session, op.output());
        }
        public <N,O> Seq<I,O> subWorkflow(String label, Built<N,O> child) {
            use(); child(session, session.root, label, child); return new Seq<>(session, child.output());
        }
    }

    private static final class ChoiceDraft {
        final String id;
        final Op<?,?> op;
        final Assessment<?> assessment;
        final List<ArmDraft> arms = new ArrayList<>();
        ChoiceDraft(String id, Op<?,?> op, Assessment<?> assessment) { this.id = id; this.op = op; this.assessment = assessment; }
        ArmDraft arm(Enum<?> outcome) { ArmDraft arm = new ArmDraft(outcome); arms.add(arm); return arm; }
        Node lower() { return new Choice(id, op, assessment, arms.stream().map(a -> new Arm(a.outcome, FluentCandidate.lower(a.nodes))).toList()); }
    }
    private static final class ArmDraft {
        final Enum<?> outcome;
        final List<Supplier<Node>> nodes = new ArrayList<>();
        ArmDraft(Enum<?> outcome) { this.outcome = outcome; }
    }
    private static ChoiceDraft choice(Session session, List<Supplier<Node>> nodes, String label, Op<?,?> op, Assessment<?> assessment) {
        ChoiceDraft choice = new ChoiceDraft(session.id(label), op, assessment); nodes.add(choice::lower); return choice;
    }

    public static final class FirstChoice<I,C,E extends Enum<E>> extends Handle {
        final ChoiceDraft choice;
        final Type incoming;
        FirstChoice(Session session, ChoiceDraft choice, Type incoming) { super(session); this.choice = choice; this.incoming = incoming; }
        public FirstArm<I,C,E,C> when(E outcome) {
            use(); return new FirstArm<>(session, choice, choice.arm(outcome), incoming, incoming);
        }
        public Seq<I,C> end() { use(); return new Seq<>(session, incoming); }
    }
    public static final class FirstArm<I,C,E extends Enum<E>,A> extends Handle {
        final ChoiceDraft choice;
        final ArmDraft arm;
        final Type incoming;
        final Type current;
        FirstArm(Session session, ChoiceDraft choice, ArmDraft arm, Type incoming, Type current) {
            super(session); this.choice = choice; this.arm = arm; this.incoming = incoming; this.current = current;
        }
        public <N,O> FirstArm<I,C,E,O> then(Op<N,O> op) {
            use(); call(session, arm.nodes, op); return new FirstArm<>(session, choice, arm, incoming, op.output());
        }
        public FirstArm<I,C,E,C> when(E outcome) {
            use(); return new FirstArm<>(session, choice, choice.arm(outcome), incoming, incoming);
        }
        public TypedChoice<I,C,E,A> terminate(Success intent) {
            Objects.requireNonNull(intent); use(); terminal(arm.nodes, Terminal.SUCCEEDED, "");
            return new TypedChoice<>(session, choice, incoming, current);
        }
        public FirstChoice<I,C,E> terminate(Failure intent, String reason) {
            Objects.requireNonNull(intent); use(); terminal(arm.nodes, Terminal.FAILED, reason);
            return new FirstChoice<>(session, choice, incoming);
        }
        public ParallelPolicy<FirstGroupContinuation<I,C,E>> parallel(String label) {
            use(); return parallelDraft(session, arm.nodes, label, null, () -> new FirstGroupContinuation<>(session, choice, arm, incoming));
        }
        public <G> ParallelPolicy<FirstArm<I,C,E,G>> parallel(String label, Class<G> result) {
            use(); return parallelDraft(session, arm.nodes, label, result, () -> new FirstArm<>(session, choice, arm, incoming, result));
        }
        public <G> ParallelPolicy<FirstArm<I,C,E,G>> parallel(String label, TypeRef<G> result) {
            use(); return parallelDraft(session, arm.nodes, label, result.type(), () -> new FirstArm<>(session, choice, arm, incoming, result.type()));
        }
        public Seq<I,A> end() { use(); return new Seq<>(session, current); }
    }
    public static final class TypedChoice<I,C,E extends Enum<E>,O> extends Handle {
        final ChoiceDraft choice;
        final Type incoming;
        final Type output;
        TypedChoice(Session session, ChoiceDraft choice, Type incoming, Type output) { super(session); this.choice = choice; this.incoming = incoming; this.output = output; }
        public TypedArm<I,C,E,O,C> when(E outcome) {
            use(); return new TypedArm<>(session, choice, choice.arm(outcome), incoming, output, incoming);
        }
        public Seq<I,O> end() { use(); return new Seq<>(session, output); }
    }
    public static final class TypedArm<I,C,E extends Enum<E>,O,A> extends Handle {
        final ChoiceDraft choice;
        final ArmDraft arm;
        final Type incoming;
        final Type output;
        final Type current;
        TypedArm(Session session, ChoiceDraft choice, ArmDraft arm, Type incoming, Type output, Type current) {
            super(session); this.choice = choice; this.arm = arm; this.incoming = incoming; this.output = output; this.current = current;
        }
        public <N,B> TypedArm<I,C,E,O,B> then(Op<N,B> op) {
            use(); call(session, arm.nodes, op); return new TypedArm<>(session, choice, arm, incoming, output, op.output());
        }
        public TypedArm<I,C,E,O,C> when(E outcome) {
            use(); return new TypedArm<>(session, choice, choice.arm(outcome), incoming, output, incoming);
        }
        public TypedChoice<I,C,E,O> terminate(Success intent) {
            Objects.requireNonNull(intent); use(); terminal(arm.nodes, Terminal.SUCCEEDED, "");
            return new TypedChoice<>(session, choice, incoming, output);
        }
        public TypedChoice<I,C,E,O> terminate(Failure intent, String reason) {
            Objects.requireNonNull(intent); use(); terminal(arm.nodes, Terminal.FAILED, reason);
            return new TypedChoice<>(session, choice, incoming, output);
        }
        public ParallelPolicy<TypedGroupContinuation<I,C,E,O>> parallel(String label) {
            use(); return parallelDraft(session, arm.nodes, label, null, () -> new TypedGroupContinuation<>(session, choice, arm, incoming, output));
        }
        public <G> ParallelPolicy<TypedArm<I,C,E,O,G>> parallel(String label, Class<G> result) {
            use(); return parallelDraft(session, arm.nodes, label, result, () -> new TypedArm<>(session, choice, arm, incoming, output, result));
        }
        public <G> ParallelPolicy<TypedArm<I,C,E,O,G>> parallel(String label, TypeRef<G> result) {
            use(); return parallelDraft(session, arm.nodes, label, result.type(), () -> new TypedArm<>(session, choice, arm, incoming, output, result.type()));
        }
        public Seq<I,A> end() { use(); return new Seq<>(session, current); }
    }

    public static final class FirstGroupContinuation<I,C,E extends Enum<E>> extends Handle {
        final ChoiceDraft choice;
        final ArmDraft arm;
        final Type incoming;
        FirstGroupContinuation(Session session, ChoiceDraft choice, ArmDraft arm, Type incoming) {
            super(session); this.choice = choice; this.arm = arm; this.incoming = incoming;
        }
        public <N,O> FirstArm<I,C,E,O> then(Op<N,O> op) {
            use(); call(session, arm.nodes, op); return new FirstArm<>(session, choice, arm, incoming, op.output());
        }
    }
    public static final class TypedGroupContinuation<I,C,E extends Enum<E>,O> extends Handle {
        final ChoiceDraft choice;
        final ArmDraft arm;
        final Type incoming;
        final Type output;
        TypedGroupContinuation(Session session, ChoiceDraft choice, ArmDraft arm, Type incoming, Type output) {
            super(session); this.choice = choice; this.arm = arm; this.incoming = incoming; this.output = output;
        }
        public <N,A> TypedArm<I,C,E,O,A> then(Op<N,A> op) {
            use(); call(session, arm.nodes, op); return new TypedArm<>(session, choice, arm, incoming, output, op.output());
        }
    }

    private static final class LoopDraft {
        final String id;
        final Op<?,Boolean> test;
        final List<Supplier<Node>> body = new ArrayList<>();
        int max;
        LimitPolicy policy = LimitPolicy.FAIL;
        LoopDraft(String id, Op<?,Boolean> test) { this.id = id; this.test = test; }
        Node lower() { return new Loop(id, test, max, policy, FluentCandidate.lower(body)); }
    }
    public static final class LoopBound<I,C> extends Handle {
        final LoopDraft loop;
        final Type incoming;
        LoopBound(Session session, LoopDraft loop, Type incoming) { super(session); this.loop = loop; this.incoming = incoming; }
        public LoopOptions<I,C> maxIterations(int maximum) {
            use(); loop.max = maximum; return new LoopOptions<>(session, loop, incoming);
        }
    }
    public static final class LoopOptions<I,C> extends Handle {
        final LoopDraft loop;
        final Type incoming;
        LoopOptions(Session session, LoopDraft loop, Type incoming) { super(session); this.loop = loop; this.incoming = incoming; }
        public LoopBody<I,C> onLimit(LimitPolicy policy) { use(); loop.policy = policy; return new LoopBody<>(session, loop, incoming); }
        public <N,O> LoopBody<I,O> then(Op<N,O> op) {
            use(); call(session, loop.body, op); return new LoopBody<>(session, loop, op.output());
        }
        public <N,O> LoopBody<I,O> subWorkflow(String label, Built<N,O> child) {
            use(); child(session, loop.body, label, child); return new LoopBody<>(session, loop, child.output());
        }
        public <N,E extends Enum<E>> LoopChoice<I,C,E> decision(String label, Op<N,E> op) {
            use(); return new LoopChoice<>(session, loop, choice(session, loop.body, label, op, null), incoming);
        }
        public <N> LoopChoice<I,C,VerdictReading> verdict(String label, Assessment<N> assessment) {
            use(); return new LoopChoice<>(session, loop, choice(session, loop.body, label, null, assessment), incoming);
        }
    }
    public static final class LoopBody<I,C> extends Handle {
        final LoopDraft loop;
        final Type current;
        LoopBody(Session session, LoopDraft loop, Type current) { super(session); this.loop = loop; this.current = current; }
        public <N,O> LoopBody<I,O> then(Op<N,O> op) {
            use(); call(session, loop.body, op); return new LoopBody<>(session, loop, op.output());
        }
        public <N,O> LoopBody<I,O> subWorkflow(String label, Built<N,O> child) {
            use(); child(session, loop.body, label, child); return new LoopBody<>(session, loop, child.output());
        }
        public LoopBody<I,C> waitFor(String label, Duration duration) {
            use(); timer(session, loop.body, label, duration); return new LoopBody<>(session, loop, current);
        }
        public <N,E extends Enum<E>> LoopChoice<I,C,E> decision(String label, Op<N,E> op) {
            use(); return new LoopChoice<>(session, loop, choice(session, loop.body, label, op, null), current);
        }
        public <N> LoopChoice<I,C,VerdictReading> verdict(String label, Assessment<N> assessment) {
            use(); return new LoopChoice<>(session, loop, choice(session, loop.body, label, null, assessment), current);
        }
        public Seq<I,C> end() { use(); return new Seq<>(session, current); }
    }

    private static final class FanDraft {
        final String id;
        final Type element;
        final List<Supplier<Node>> body = new ArrayList<>();
        int maximum;
        int concurrency = 1;
        boolean policy;
        FanDraft(String id, Type element) { this.id = id; this.element = element; }
        Node lower() { return new Fan(id, element, maximum, concurrency, policy, FluentCandidate.lower(body)); }
    }
    public static final class FanBound<I> extends Handle {
        final FanDraft fan;
        FanBound(Session session, FanDraft fan) { super(session); this.fan = fan; }
        public FanPolicy<I> maxItems(int maximum) { use(); fan.maximum = maximum; return new FanPolicy<>(session, fan); }
    }
    public static final class FanPolicy<I> extends Handle {
        final FanDraft fan;
        FanPolicy(Session session, FanDraft fan) { super(session); this.fan = fan; }
        public FanReady<I> maxInFlight(int concurrency) { use(); fan.concurrency = concurrency; return new FanReady<>(session, fan); }
        public FanStart<I> allSuccessful() { use(); fan.policy = true; return new FanStart<>(session, fan); }
    }
    public static final class FanReady<I> extends Handle {
        final FanDraft fan;
        FanReady(Session session, FanDraft fan) { super(session); this.fan = fan; }
        public FanStart<I> allSuccessful() { use(); fan.policy = true; return new FanStart<>(session, fan); }
    }
    public static final class FanStart<I> extends Handle {
        final FanDraft fan;
        FanStart(Session session, FanDraft fan) { super(session); this.fan = fan; }
        public <N,O> FanBody<I,O> then(Op<N,O> op) { use(); call(session, fan.body, op); return new FanBody<>(session, fan, op.output()); }
        public <N,O> FanBody<I,O> subWorkflow(String label, Built<N,O> child) {
            use(); child(session, fan.body, label, child); return new FanBody<>(session, fan, child.output());
        }
    }
    public static final class FanBody<I,C> extends Handle {
        final FanDraft fan;
        final Type current;
        FanBody(Session session, FanDraft fan, Type current) { super(session); this.fan = fan; this.current = current; }
        public <N,O> FanBody<I,O> then(Op<N,O> op) { use(); call(session, fan.body, op); return new FanBody<>(session, fan, op.output()); }
        public <N,E extends Enum<E>> FanChoice<I,C,E> decision(String label, Op<N,E> op) {
            use(); return new FanChoice<>(session, fan, choice(session, fan.body, label, op, null), current);
        }
        public <N> FanChoice<I,C,VerdictReading> verdict(String label, Assessment<N> assessment) {
            use(); return new FanChoice<>(session, fan, choice(session, fan.body, label, null, assessment), current);
        }
        public Seq<I,List<C>> end() { use(); return new Seq<>(session, new ListType(current)); }
    }

    public static final class LoopChoice<I,C,E extends Enum<E>> extends Handle {
        final LoopDraft loop;
        final ChoiceDraft choice;
        final Type incoming;
        LoopChoice(Session session, LoopDraft loop, ChoiceDraft choice, Type incoming) {
            super(session); this.loop = loop; this.choice = choice; this.incoming = incoming;
        }
        public LoopArm<I,C,E,C> when(E outcome) {
            use(); return new LoopArm<>(session, loop, choice, choice.arm(outcome), incoming, incoming);
        }
        public LoopBody<I,C> end() { use(); return new LoopBody<>(session, loop, incoming); }
    }
    public static final class LoopArm<I,C,E extends Enum<E>,A> extends Handle {
        final LoopDraft loop;
        final ChoiceDraft choice;
        final ArmDraft arm;
        final Type incoming;
        final Type current;
        LoopArm(Session session, LoopDraft loop, ChoiceDraft choice, ArmDraft arm, Type incoming, Type current) {
            super(session); this.loop = loop; this.choice = choice; this.arm = arm; this.incoming = incoming; this.current = current;
        }
        public <N,O> LoopArm<I,C,E,O> then(Op<N,O> op) {
            use(); call(session, arm.nodes, op); return new LoopArm<>(session, loop, choice, arm, incoming, op.output());
        }
        public LoopArm<I,C,E,C> when(E outcome) {
            use(); return new LoopArm<>(session, loop, choice, choice.arm(outcome), incoming, incoming);
        }
        public LoopBody<I,A> end() { use(); return new LoopBody<>(session, loop, current); }
    }

    public static final class FanChoice<I,C,E extends Enum<E>> extends Handle {
        final FanDraft fan;
        final ChoiceDraft choice;
        final Type incoming;
        FanChoice(Session session, FanDraft fan, ChoiceDraft choice, Type incoming) {
            super(session); this.fan = fan; this.choice = choice; this.incoming = incoming;
        }
        public FanArm<I,C,E,C> when(E outcome) {
            use(); return new FanArm<>(session, fan, choice, choice.arm(outcome), incoming, incoming);
        }
        public FanBody<I,C> end() { use(); return new FanBody<>(session, fan, incoming); }
    }
    public static final class FanArm<I,C,E extends Enum<E>,A> extends Handle {
        final FanDraft fan;
        final ChoiceDraft choice;
        final ArmDraft arm;
        final Type incoming;
        final Type current;
        FanArm(Session session, FanDraft fan, ChoiceDraft choice, ArmDraft arm, Type incoming, Type current) {
            super(session); this.fan = fan; this.choice = choice; this.arm = arm; this.incoming = incoming; this.current = current;
        }
        public <N,O> FanArm<I,C,E,O> then(Op<N,O> op) {
            use(); call(session, arm.nodes, op); return new FanArm<>(session, fan, choice, arm, incoming, op.output());
        }
        public FanArm<I,C,E,C> when(E outcome) {
            use(); return new FanArm<>(session, fan, choice, choice.arm(outcome), incoming, incoming);
        }
        public FanBody<I,A> end() { use(); return new FanBody<>(session, fan, current); }
    }

    private static final class ParallelDraft {
        final String id;
        final Type output;
        final List<MemberDraft> members = new ArrayList<>();
        boolean policy;
        ParallelDraft(String id, Type output) { this.id = id; this.output = output; }
        MemberDraft member(String name) { MemberDraft member = new MemberDraft(name); members.add(member); return member; }
        Node lower() { return new Parallel(id, output, policy, members.stream().map(m -> new Member(m.name, FluentCandidate.lower(m.nodes))).toList()); }
    }
    private static final class MemberDraft {
        final String name;
        final List<Supplier<Node>> nodes = new ArrayList<>();
        MemberDraft(String name) { this.name = name; }
    }
    private static <P> ParallelPolicy<P> parallelDraft(Session session, List<Supplier<Node>> nodes, String label, Type output, Supplier<P> parent) {
        ParallelDraft group = new ParallelDraft(session.id(label), output); nodes.add(group::lower);
        return new ParallelPolicy<>(session, group, parent);
    }
    public static final class ParallelPolicy<P> extends Handle {
        final ParallelDraft group;
        final Supplier<P> parent;
        ParallelPolicy(Session session, ParallelDraft group, Supplier<P> parent) { super(session); this.group = group; this.parent = parent; }
        public Branches<P> allSuccessful() { use(); group.policy = true; return new Branches<>(session, group, parent); }
    }
    public static final class Branches<P> extends Handle {
        final ParallelDraft group;
        final Supplier<P> parent;
        Branches(Session session, ParallelDraft group, Supplier<P> parent) { super(session); this.group = group; this.parent = parent; }
        public MemberBody<P> branch(String name) { use(); return new MemberBody<>(session, group, group.member(name), parent); }
    }
    public static final class MemberBody<P> extends Handle {
        final ParallelDraft group;
        final MemberDraft member;
        final Supplier<P> parent;
        MemberBody(Session session, ParallelDraft group, MemberDraft member, Supplier<P> parent) {
            super(session); this.group = group; this.member = member; this.parent = parent;
        }
        public <N,O> MemberBody<P> then(Op<N,O> op) {
            use(); call(session, member.nodes, op); return new MemberBody<>(session, group, member, parent);
        }
        public <N,O> MemberBody<P> subWorkflow(String label, Built<N,O> child) {
            use(); child(session, member.nodes, label, child); return new MemberBody<>(session, group, member, parent);
        }
        public MemberBody<P> waitFor(String label, Duration duration) {
            use(); timer(session, member.nodes, label, duration); return new MemberBody<>(session, group, member, parent);
        }
        public <N,E extends Enum<E>> LocalChoice<MemberBody<P>,E> decision(String label, Op<N,E> op) {
            use(); return new LocalChoice<>(session, choice(session, member.nodes, label, op, null), () -> new MemberBody<>(session, group, member, parent));
        }
        public <N> LocalChoice<MemberBody<P>,VerdictReading> verdict(String label, Assessment<N> assessment) {
            use(); return new LocalChoice<>(session, choice(session, member.nodes, label, null, assessment), () -> new MemberBody<>(session, group, member, parent));
        }
        public MemberBody<P> branch(String name) { use(); return new MemberBody<>(session, group, group.member(name), parent); }
        public P end() { use(); return parent.get(); }
    }
    public static final class LocalChoice<P,E extends Enum<E>> extends Handle {
        final ChoiceDraft choice;
        final Supplier<P> parent;
        LocalChoice(Session session, ChoiceDraft choice, Supplier<P> parent) { super(session); this.choice = choice; this.parent = parent; }
        public LocalArm<P,E> when(E outcome) { use(); return new LocalArm<>(session, choice, choice.arm(outcome), parent); }
        public P end() { use(); return parent.get(); }
    }
    public static final class LocalArm<P,E extends Enum<E>> extends Handle {
        final ChoiceDraft choice;
        final ArmDraft arm;
        final Supplier<P> parent;
        LocalArm(Session session, ChoiceDraft choice, ArmDraft arm, Supplier<P> parent) { super(session); this.choice = choice; this.arm = arm; this.parent = parent; }
        public <N,O> LocalArm<P,E> then(Op<N,O> op) { use(); call(session, arm.nodes, op); return new LocalArm<>(session, choice, arm, parent); }
        public LocalArm<P,E> when(E outcome) { use(); return new LocalArm<>(session, choice, choice.arm(outcome), parent); }
        public P end() { use(); return parent.get(); }
    }
}
