package io.github.markpollack.workflow.batch.durable;

import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Mutable authoring facade for a sequential workflow using fixed supplied registrations.
 * Each then() reads the Step's concrete declaration through ApplicationDeployment;
 * build() delegates binding, structural and codec validation to the existing compiler
 * foundation. The result owns immutable definition/selection data, not a mutable builder
 * or run state. This builder is not thread safe and neither persists facts nor executes
 * application Step code. Registration supplies types and implementation selection; it
 * does not determine order. This convenience facade exposes a single mutable stage, so
 * its fluent call order is guarded at build time rather than by staged Java interfaces.
 * For programmatic construction, create WorkflowModel.Definition/Call data and call
 * ValidatedWorkflow.compileSequential through the same validation boundary.
 */
public final class SequentialWorkflow {

	private final ApplicationDeployment deployment;

	private final String name;

	private final List<Call> calls = new ArrayList<>();

	private final List<ExecutableIdentity> selections = new ArrayList<>();

	private End end;

	private Duration duration;

	SequentialWorkflow(ApplicationDeployment deployment, String name) {
		this.deployment = deployment;
		this.name = Objects.requireNonNull(name);
	}

	/**
	 * Place a registered step; a repeated registration requires a distinct authored
	 * placement.
	 */
	public SequentialWorkflow then(String registration) {
		return then(registration, registration);
	}

	/**
	 * Place the selected supplied step at a stable authored location. The placement is
	 * distinct from the lookup name: then("first", "greet") and then("second", "greet")
	 * select the same object but produce distinct logical invocations. A rebuilt
	 * definition preserves these locations when its name, order and labels are unchanged.
	 */
	public SequentialWorkflow then(String placement, String registration) {
		if (end != null)
			throw new IllegalStateException("definition is already terminated");
		ExecutableIdentity selected = deployment.selection(registration);
		calls.add(new Call(placement, Op.declared(placement, selected.input(), selected.output())));
		selections.add(selected);
		return this;
	}

	/** Apply an optional tighter finite bound through the compiler's existing policy. */
	public SequentialWorkflow maxDuration(Duration duration) {
		if (this.duration != null)
			throw new IllegalStateException("duration already configured");
		this.duration = Objects.requireNonNull(duration);
		return this;
	}

	/**
	 * Add an explicit terminal declaration. This convenience method supplies no reason,
	 * so only SUCCEEDED can pass validation; FAILED and CANCELLED require a reason in a
	 * programmatically constructed WorkflowModel.End. A terminal is never inferred from
	 * the last step or from calling build().
	 */
	public SequentialWorkflow terminate(Terminal terminal) {
		if (end != null)
			throw new IllegalStateException("terminal already configured");
		end = new End(Objects.requireNonNull(terminal), "");
		return this;
	}

	/**
	 * Validate this sequence and return an immutable definition ready for admission. The
	 * first Step determines the root input type and the last Step the output type. All
	 * intermediate inputs are derived from legal typed values, including record assembly
	 * and earlier saved inputs; missing or ambiguous bindings refuse.
	 * <p>
	 * ValidatedWorkflow.compileSequential assigns stable placements and invokes the
	 * common structure/binding/codec analysis. Java compilation alone cannot establish
	 * these path-dependent rules. No Step or database operation occurs here.
	 * @return validated definition, invocation/value recipes and executable selections
	 * @throws IllegalStateException when steps or an explicit terminal are absent
	 * @throws IllegalArgumentException when structure, bindings or value shapes are
	 * invalid
	 */
	public ValidatedWorkflow build() {
		if (calls.isEmpty() || end == null)
			throw new IllegalStateException("steps and explicit terminal required");
		List<Node> nodes = new ArrayList<>(calls);
		nodes.add(end);
		var definition = new Definition<>(name, selections.getFirst().input(), selections.getLast().output(), nodes,
				duration);
		return ValidatedWorkflow.compileSequential(definition, selections, DeadlinePolicy.DEFAULT);
	}

}
