package io.github.markpollack.workflow.batch.durable;

import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.ValueId;

/**
 * One exact-value path for root and nested execution. Missing historical inputs never
 * rebuild.
 */
final class ScopedValues {

	private ScopedValues() {
	}

	static ValidatedWorkflow.ValueRecipe root(ValidatedWorkflow workflow) {
		return workflow.values()
			.values()
			.stream()
			.filter(v -> v.identity().role().equals("root"))
			.findFirst()
			.orElseThrow(() -> new WorkflowRefusal("BINDING_INVALID", "compiler root recipe missing"));
	}

	static RunState.Scope owner(RunState run, RunState.Scope scope, ValueId id) {
		String path = id.placement().graphName();
		RunState.Scope current = scope;
		Set<String> seen = new HashSet<>();
		while (!current.group.isEmpty() && !path.startsWith(current.memberPath)) {
			if (!seen.add(current.id))
				throw new WorkflowRefusal("PROGRESS_INVALID", "cyclic value ancestry");
			current = run.scopes.get(current.parent);
			if (current == null)
				throw new WorkflowRefusal("PROGRESS_INVALID", "missing value ancestor");
		}
		RunState.Scope found = current;
		boolean more;
		do {
			more = false;
			for (var child : run.scopes.values())
				if (!child.group.isEmpty() && child.parent.equals(found.id) && path.startsWith(child.memberPath)) {
					if (!seen.add(child.id))
						throw new WorkflowRefusal("PROGRESS_INVALID", "cyclic value membership");
					found = child;
					more = true;
					break;
				}
		}
		while (more);
		return found;
	}

	static String id(RunState run, RunState.Scope scope, ValueId value) {
		return ScopeIds.value(run.id, owner(run, scope, value), value);
	}

	/**
	 * Structural products retain member references, never an invented application codec.
	 */
	static String result(RunState run, RunState.Scope scope, ValueId id, ValidatedWorkflow workflow) {
		var recipe = workflow.values().get(id);
		if (recipe == null)
			throw new WorkflowRefusal("BINDING_INVALID", "missing result recipe");
		if (recipe.contract() != null)
			return verify(run, scope, id, workflow).id;
		var owner = owner(run, scope, id);
		if (id.role().startsWith("capture:")) {
			var capture = workflow.captures()
				.stream()
				.filter(c -> c.result().identity().equals(id))
				.findFirst()
				.orElseThrow(() -> new WorkflowRefusal("PROGRESS_INVALID", "missing structural capture"));
			var receipt = run.decisions.get(ScopeIds.invocation(run.id, owner.id, id.placement().graphName()));
			RunIntegrity.require(receipt != null, "structural capture without decision");
			var selected = capture.routes().get(receipt.outcome());
			RunIntegrity.require(selected != null, "structural capture without selected source");
			String source = result(run, owner, selected.identity(), workflow);
			RunIntegrity.require(source.equals(receipt.captures().get(id(run, owner, id))),
					"structural capture source differs");
			return id(run, owner, id);
		}
		var group = run.groups.get(ParallelProgress.identity(owner, id.placement().graphName()));
		RunIntegrity.require(group != null && group.phase.equals("SETTLED") && owner.nodes.containsKey(group.join),
				"structural product without successful settlement");
		for (ValueId component : recipe.components())
			result(run, owner, component, workflow);
		return id(run, owner, id);
	}

	static String codec(TypeContracts.CodecIdentity codec) {
		return Digests.fields(codec.name(), codec.version(), codec.configuration());
	}

	static void put(RunState run, RunState.Scope scope, ValidatedWorkflow.ValueRecipe recipe, byte[] bytes,
			String producer, String source) {
		scope = owner(run, scope, recipe.identity());
		var value = new RunState.Value();
		value.id = id(run, scope, recipe.identity());
		value.scope = scope.id;
		value.definition = scope.definition;
		value.local = ScopeIds.localValue(recipe.identity());
		value.type = recipe.contract().javaType();
		value.shape = recipe.contract().shapeDigest();
		value.codec = codec(recipe.contract().codec());
		value.digest = Digests.of(bytes);
		value.payload = bytes.clone();
		value.producer = producer;
		value.source = source;
		var owning = scope;
		value.components = recipe.components().stream().map(id -> id(run, owning, id)).toList();
		value.consumed = recipe.consumed().stream().map(id -> id(run, owning, id)).toList();
		if (run.values.putIfAbsent(value.id, value) != null)
			throw new WorkflowRefusal("VALUE_IMMUTABLE", "cannot overwrite immutable value");
	}

	static RunState.Value verify(RunState run, RunState.Scope scope, ValueId id, ValidatedWorkflow workflow) {
		scope = owner(run, scope, id);
		var recipe = workflow.values().get(id);
		var value = run.values.get(id(run, scope, id));
		if (value == null || recipe == null)
			throw new WorkflowRefusal("VALUE_MISSING", "committed selected value is missing");
		var owning = scope;
		String producer = id.role().equals("root") ? scope.opening
				: ScopeIds.invocation(run.id, scope.id, id.placement().graphName());
		if (id.role().equals("product"))
			producer = Digests.fields("group-v1", scope.id, id.placement().graphName());
		String source = "";
		if (id.role().equals("root") && !scope.parent.isEmpty()) {
			source = WorkflowProgress.invocation(run, scope.opening).input;
		}
		else if (id.role().startsWith("capture:")) {
			var accepted = run.decisions.get(producer);
			if (accepted == null || !accepted.captures().containsKey(value.id))
				throw new WorkflowRefusal("VALUE_CHANGED", "capture acceptance missing");
			source = accepted.captures().get(value.id);
		}
		else if (!id.role().equals("root")) {
			var call = WorkflowProgress.find(run, scope.id, id.placement().graphName());
			if (call != null && call.kind.equals("COMPOSITE") && call.output.equals(value.id)
					&& call.status.equals("SETTLED"))
				source = run.scopes.get(call.child).localOutcome.successValue();
		}
		if (!producer.equals(value.producer) || !value.id.equals(id(run, scope, id)) || !value.scope.equals(scope.id)
				|| !value.definition.equals(scope.definition) || !value.local.equals(ScopeIds.localValue(id))
				|| !value.source.equals(source) || !value.type.equals(recipe.contract().javaType())
				|| !value.shape.equals(recipe.contract().shapeDigest())
				|| !value.codec.equals(codec(recipe.contract().codec()))
				|| !value.digest.equals(Digests.of(value.payload))
				|| !value.components.equals(recipe.components().stream().map(v -> id(run, owning, v)).toList())
				|| !value.consumed.equals(recipe.consumed().stream().map(v -> id(run, owning, v)).toList()))
			throw new WorkflowRefusal("VALUE_CHANGED", "persisted value identity/type/codec/provenance differs");
		return value;
	}

	static Object decode(RunState run, RunState.Scope scope, ValueId id, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved) {
		return resolved.decode(verify(run, scope, id, workflow).payload, workflow.values().get(id).declaration());
	}

	static void materialize(RunState run, RunState.Scope scope, ValueId id, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved, String producer) {
		if (run.values.containsKey(id(run, scope, id))) {
			verify(run, scope, id, workflow);
			return;
		}
		var recipe = workflow.values().get(id);
		if (recipe == null || recipe.components().isEmpty())
			throw new WorkflowRefusal("VALUE_MISSING", "selected immutable value missing: " + id);
		List<Object> components = new ArrayList<>();
		for (ValueId component : recipe.components())
			components.add(decode(run, scope, component, workflow, resolved));
		Object input = resolved.assemble(recipe.declaration(), components);
		put(run, scope, recipe, resolved.encode(input, recipe.declaration()), producer, "");
	}

}
