package io.github.markpollack.workflow.batch.durable;

import java.util.Map;
import io.github.markpollack.workflow.flows.Step;

/**
 * Test fixture grouping the two public runtime arguments; not an authoring or runtime
 * facade.
 */
public record TestApplication(StepRegistry registry, ExecutionCompatibility compatibility) {
	public TestApplication(String app, String build, Map<String, String> config,
			Map<String, ? extends Step<?, ?>> steps) {
		this(StepRegistry.of(steps), new ExecutionCompatibility(app, build, config));
	}

	public Step<?, ?> step(String name) {
		Step<?, ?> step = registry.steps().get(name);
		if (step == null)
			throw new WorkflowRefusal("STEP_UNAVAILABLE", "missing test registration: " + name);
		return step;
	}

	public ExecutionCompatibility.Manifest manifest() {
		return compatibility.manifest();
	}
}
