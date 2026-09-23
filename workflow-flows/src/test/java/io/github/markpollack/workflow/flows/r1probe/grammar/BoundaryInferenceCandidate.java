package io.github.markpollack.workflow.flows.r1probe.grammar;

import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.Operation;
import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.Terminal;

/** Compile-only signatures; intentionally has no builder implementation. */
public final class BoundaryInferenceCandidate {
    private BoundaryInferenceCandidate() {}

    public static Entry define(String name) { throw new UnsupportedOperationException("compile-only"); }

    public interface Workflow<I, O> {}
    public interface Entry {
        <I, O> Sequence<I, O> then(Operation<I, O> operation);
    }
    public interface Sequence<I, C> {
        <N, O> Sequence<I, O> then(Operation<N, O> operation);
        <N, E extends Enum<E>> FirstChoice<I, C, E> decision(String id, Operation<N, E> operation);
        Closed<I, C> terminate(Terminal terminal);
    }
    public interface Closed<I, O> {
        Workflow<I, O> build();
    }
    public interface FirstChoice<I, C, E extends Enum<E>> {
        FirstArm<I, C, E, C> when(E outcome);
    }
    public interface FirstArm<I, C, E extends Enum<E>, A> {
        <N, O> FirstArm<I, C, E, O> then(Operation<N, O> operation);
        TypedChoice<I, C, E, A> terminate(Terminal terminal);
    }
    public interface TypedChoice<I, C, E extends Enum<E>, O> {
        LaterArm<I, C, E, O, C> when(E outcome);
        Closed<I, O> end();
    }
    public interface LaterArm<I, C, E extends Enum<E>, O, A> {
        <N, B> LaterArm<I, C, E, O, B> then(Operation<N, B> operation);
        TypedChoice<I, C, E, O> terminate(Terminal terminal);
    }
}
