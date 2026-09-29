package io.github.markpollack.workflow.batch.durable;

import java.util.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.compiler.StepTypes;

/**
 * Fixed canonical names for supplied Step objects. Order never defines topology.
 * Applications own object construction, thread safety and dependency lifecycle. Copying
 * this map does not freeze object internals. Aliases for the same instance are refused.
 */
public final class StepRegistry {

	private final Map<String, Step<?, ?>> steps;

	private final Map<Step<?, ?>, String> names;

	private StepRegistry(Map<String, ? extends Step<?, ?>> supplied) {
		Map<String, Step<?, ?>> copy = new LinkedHashMap<>();
		IdentityHashMap<Step<?, ?>, String> reverse = new IdentityHashMap<>();
		supplied.forEach((name, step) -> {
			requireName(name);
			Objects.requireNonNull(step, "step");
			if (reverse.put(step, name) != null)
				throw new WorkflowRefusal("STEP_ALIAS", "one instance has multiple registration names");
			try {
				StepTypes.of(step.getClass());
			}
			catch (IllegalArgumentException | LinkageError ex) {
				throw new WorkflowRefusal("STEP_CONTRACT", "unsupported Step declaration: " + name, ex);
			}
			copy.put(name, step);
		});
		steps = Collections.unmodifiableMap(copy);
		names = Collections.unmodifiableMap(reverse);
	}

	/**
	 * Snapshot canonical container names; upstream overwritten duplicate keys cannot be
	 * detected.
	 */
	public static StepRegistry of(Map<String, ? extends Step<?, ?>> steps) {
		return new StepRegistry(steps);
	}

	/** Builder detects duplicate names before map folding. */
	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private final Map<String, Step<?, ?>> steps = new LinkedHashMap<>();

		public Builder register(String name, Step<?, ?> step) {
			requireName(name);
			Objects.requireNonNull(step, "step");
			if (steps.putIfAbsent(name, step) != null)
				throw new WorkflowRefusal("STEP_DUPLICATE", "registration already exists: " + name);
			return this;
		}

		public StepRegistry build() {
			return StepRegistry.of(steps);
		}

	}

	/** Immutable registration view, useful for application inspection. */
	public Map<String, Step<?, ?>> steps() {
		return steps;
	}

	String nameOf(Step<?, ?> step) {
		String name = names.get(step);
		if (name == null)
			throw new WorkflowRefusal("STEP_UNAVAILABLE", "supplied Step instance is not registered");
		return name;
	}

	private static void requireName(String name) {
		if (name == null || name.isBlank() || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(name))
			throw new IllegalArgumentException("well-formed nonblank registration name required");
	}

}
