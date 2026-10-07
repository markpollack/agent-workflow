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

	static String codec(TypeContracts.CodecIdentity codec) {
		return Digests.fields(codec.name(), codec.version(), codec.configuration());
	}

	static void put(RunState run, RunState.Scope scope, ValidatedWorkflow.ValueRecipe recipe, byte[] bytes,
			String producer, String source) {
		var value = new RunState.Value();
		value.id = ScopeIds.value(run.id, scope, recipe.identity());
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
		value.components = recipe.components().stream().map(id -> ScopeIds.value(run.id, scope, id)).toList();
		value.consumed = recipe.consumed().stream().map(id -> ScopeIds.value(run.id, scope, id)).toList();
		if (run.values.putIfAbsent(value.id, value) != null)
			throw new WorkflowRefusal("VALUE_IMMUTABLE", "cannot overwrite immutable value");
	}

	static RunState.Value verify(RunState run, RunState.Scope scope, ValueId id, ValidatedWorkflow workflow) {
		var recipe = workflow.values().get(id);
		var value = run.values.get(ScopeIds.value(run.id, scope, id));
		if (value == null || recipe == null)
			throw new WorkflowRefusal("VALUE_MISSING", "committed selected value is missing");
		String producer = id.role().equals("root") ? scope.opening
				: ScopeIds.invocation(run.id, scope.id, id.placement().graphName());
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
		if (!producer.equals(value.producer) || !value.id.equals(ScopeIds.value(run.id, scope, id))
				|| !value.scope.equals(scope.id) || !value.definition.equals(scope.definition)
				|| !value.local.equals(ScopeIds.localValue(id)) || !value.source.equals(source)
				|| !value.type.equals(recipe.contract().javaType())
				|| !value.shape.equals(recipe.contract().shapeDigest())
				|| !value.codec.equals(codec(recipe.contract().codec()))
				|| !value.digest.equals(Digests.of(value.payload))
				|| !value.components
					.equals(recipe.components().stream().map(v -> ScopeIds.value(run.id, scope, v)).toList())
				|| !value.consumed
					.equals(recipe.consumed().stream().map(v -> ScopeIds.value(run.id, scope, v)).toList()))
			throw new WorkflowRefusal("VALUE_CHANGED", "persisted value identity/type/codec/provenance differs");
		return value;
	}

	static Object decode(RunState run, RunState.Scope scope, ValueId id, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved) {
		return resolved.decode(verify(run, scope, id, workflow).payload, workflow.values().get(id).declaration());
	}

	static void materialize(RunState run, RunState.Scope scope, ValueId id, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved, String producer) {
		if (run.values.containsKey(ScopeIds.value(run.id, scope, id))) {
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
