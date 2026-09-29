package io.github.markpollack.workflow.flows;

import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Fluent construction of validated sequential workflows from application-supplied Steps.
 * The first Step's input and last Step's output determine the sequential boundary types.
 * This convenience does not define inference for branching or looping workflows.
 * <p>
 * Each {@code then} adds an authored position, even when the same object is reused.
 * Optional labels name those positions, not registry entries or Spring beans. Unlabeled
 * calls receive positional labels ({@code step-1}, {@code step-2}, ...); neither form
 * promises stable identity across edits to the workflow.
 */
public final class Workflows {

	private Workflows() {
	}

	/**
	 * Begin a definition; no registry, database or application execution is involved.
	 * @param name authored workflow name, contributing to placement identity
	 * @return the stage requiring at least one Step before termination
	 */
	public static Start define(String name) {
		return new Builder(name);
	}

	/** First stage: at least one supplied Step is required before a terminal. */
	public interface Start {

		Sequence then(Step<?, ?> step);

		Sequence then(String label, Step<?, ?> step);

		Start maxDuration(Duration duration);

	}

	/** Nonempty sequence; another Step or an explicit terminal may follow. */
	public interface Sequence {

		Sequence then(Step<?, ?> step);

		Sequence then(String label, Step<?, ?> step);

		Sequence maxDuration(Duration duration);

		Closed terminate(Terminal terminal);

		Closed terminate(Terminal terminal, String reason);

	}

	/** Terminal stage: build is the only remaining authoring operation. */
	public interface Closed {

		/**
		 * Snapshot and validate the complete definition and full generic bindings.
		 * @return an immutable checked graph and its separate supplied-object associations
		 * @throws IllegalArgumentException if structure, types or value bindings are invalid
		 */
		ValidatedWorkflow build();

	}

	private static final class Builder implements Start, Sequence, Closed {

		private final String name;

		private final List<Entry> entries = new ArrayList<>();

		private End end;

		private Duration duration;

		Builder(String name) {
			this.name = Objects.requireNonNull(name);
		}

		public Sequence then(Step<?, ?> step) {
			return then("step-" + (entries.size() + 1), step);
		}

		public Sequence then(String label, Step<?, ?> step) {
			requireOpen();
			StepTypes types = StepTypes.of(Objects.requireNonNull(step).getClass());
			entries.add(new Entry(new Call(label, Op.declared(label, types.input(), types.output())), step));
			return this;
		}

		public Builder maxDuration(Duration duration) {
			requireOpen();
			if (this.duration != null)
				throw new IllegalStateException("duration already configured");
			this.duration = Objects.requireNonNull(duration);
			return this;
		}

		public Closed terminate(Terminal terminal) {
			return terminate(terminal, "");
		}

		public Closed terminate(Terminal terminal, String reason) {
			requireOpen();
			end = new End(Objects.requireNonNull(terminal), reason);
			return this;
		}

		public ValidatedWorkflow build() {
			if (entries.isEmpty() || end == null)
				throw new IllegalStateException("steps and explicit terminal required");
			List<Node> nodes = new ArrayList<>();
			entries.forEach(entry -> nodes.add(entry.call()));
			nodes.add(end);
			var first = entries.getFirst().call().operation();
			var last = entries.getLast().call().operation();
			var definition = new Definition<>(name, first.input(), last.output(), nodes, duration);
			return ValidatedWorkflow.compileSequential(definition, entries.stream().map(Entry::step).toList(),
					DeadlinePolicy.DEFAULT);
		}

		/** One authored occurrence and its supplied implementation travel together. */
		private record Entry(Call call, Step<?, ?> step) {
		}

		private void requireOpen() {
			if (end != null)
				throw new IllegalStateException("definition already terminated");
		}

	}

}
