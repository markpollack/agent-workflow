package io.github.markpollack.workflow.flows;

import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Fluent construction of validated sequential workflows from application-supplied Steps.
 */
public final class Workflows {

	private Workflows() {
	}

	/** Begin a definition; no registry, database or application execution is involved. */
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

		/** Snapshot and validate the complete definition and full generic bindings. */
		ValidatedWorkflow build();

	}

	private static final class Builder implements Start, Sequence, Closed {

		private final String name;

		private final List<Call> calls = new ArrayList<>();

		private final List<Step<?, ?>> steps = new ArrayList<>();

		private End end;

		private Duration duration;

		Builder(String name) {
			this.name = Objects.requireNonNull(name);
		}

		public Sequence then(Step<?, ?> step) {
			return then("step-" + (calls.size() + 1), step);
		}

		public Sequence then(String label, Step<?, ?> step) {
			requireOpen();
			StepTypes types = StepTypes.of(Objects.requireNonNull(step).getClass());
			calls.add(new Call(label, Op.declared(label, types.input(), types.output())));
			steps.add(step);
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
			if (calls.isEmpty() || end == null)
				throw new IllegalStateException("steps and explicit terminal required");
			List<Node> nodes = new ArrayList<>(calls);
			nodes.add(end);
			var first = StepTypes.of(steps.getFirst().getClass());
			var last = StepTypes.of(steps.getLast().getClass());
			var definition = new Definition<>(name, first.input(), last.output(), nodes, duration);
			return ValidatedWorkflow.compileSequential(definition, steps, DeadlinePolicy.DEFAULT);
		}

		private void requireOpen() {
			if (end != null)
				throw new IllegalStateException("definition already terminated");
		}

	}

}
