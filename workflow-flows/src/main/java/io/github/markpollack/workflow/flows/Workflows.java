package io.github.markpollack.workflow.flows;

import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Fluent construction of validated workflows from application-supplied Steps. The first
 * call's input and last call's output determine the boundary types. A call may be a Step
 * or a reusable validated definition. This convenience does not define inference for
 * branching or looping workflows.
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
	 * @return the stage requiring at least one Step or composite before termination
	 */
	public static Start define(String name) {
		return new Builder(name);
	}

	/** First stage: at least one Step or composite is required before a terminal. */
	public interface Start {

		Sequence then(Step<?, ?> step);

		Sequence then(String label, Step<?, ?> step);

		/**
		 * Invoke a reusable definition with private values and a local return in this
		 * run. The same definition can be placed repeatedly and nested in other
		 * definitions.
		 * @param label authored call position, independent of registration names
		 * @param workflow immutable validated callee, sharing the caller's deployment
		 * @return the continuing authoring stage
		 */
		Sequence subWorkflow(String label, ValidatedWorkflow workflow);

		Start maxDuration(Duration duration);

	}

	/** Nonempty sequence; another call or an explicit terminal may follow. */
	public interface Sequence {

		Sequence then(Step<?, ?> step);

		Sequence then(String label, Step<?, ?> step);

		/**
		 * Invoke a reusable definition with private values and a local return in this
		 * run. The same definition can be placed repeatedly and nested in other
		 * definitions.
		 * @param label authored call position, independent of registration names
		 * @param workflow immutable validated callee, sharing the caller's deployment
		 * @return the continuing authoring stage
		 */
		Sequence subWorkflow(String label, ValidatedWorkflow workflow);

		Sequence maxDuration(Duration duration);

		Closed terminate(Terminal terminal);

		Closed terminate(Terminal terminal, String reason);

	}

	/** Terminal stage: build is the only remaining authoring operation. */
	public interface Closed {

		/**
		 * Snapshot and validate the complete definition and full generic bindings.
		 * @return an immutable checked graph and its separate supplied-object
		 * associations
		 * @throws IllegalArgumentException if structure, types or value bindings are
		 * invalid
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
			entries.add(new Entry(new Call(label, Op.declared(label, types.input(), types.output())), step,
					types.input(), types.output()));
			return this;
		}

		public Sequence subWorkflow(String label, ValidatedWorkflow workflow) {
			requireOpen();
			Objects.requireNonNull(workflow);
			entries.add(new Entry(new Child(label, workflow), null, workflow.definition().input(),
					workflow.definition().output()));
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
				throw new IllegalStateException("calls and explicit terminal required");
			List<Node> nodes = new ArrayList<>();
			entries.forEach(entry -> nodes.add(entry.call()));
			nodes.add(end);
			var first = entries.getFirst();
			var last = entries.getLast();
			var definition = new Definition<>(name, first.input(), last.output(), nodes, duration);
			return ValidatedWorkflow.compile(definition,
					entries.stream().map(Entry::step).filter(Objects::nonNull).toList(), DeadlinePolicy.DEFAULT);
		}

		/** One authored occurrence and its supplied implementation travel together. */
		private record Entry(Node call, Step<?, ?> step, java.lang.reflect.Type input, java.lang.reflect.Type output) {
		}

		private void requireOpen() {
			if (end != null)
				throw new IllegalStateException("definition already terminated");
		}

	}

}
