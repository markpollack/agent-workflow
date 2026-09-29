package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.workflow.WorkflowNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.workflow.flows.compiler.TypeContracts;
import io.github.markpollack.workflow.flows.compiler.StepTypes;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;

/**
 * The checked boundary between one immutable validated workflow and the application's
 * supplied Step objects. Constructed during admission and when resolving execution/result
 * access, it verifies codec/type contracts, deployment selections and each actual generic
 * Step declaration. It owns no threads, persistence, instances or dependency lifecycle.
 * <p>
 * DurableWorkflows selects and verifies saved values using the validated recipes, asks
 * this object to decode or assemble them, then invokes the selected object directly. The
 * codec is shared with definition validation; there is no second binding resolver.
 */
final class WorkflowExecutionBindings {

	private final TypeContracts codec = new TypeContracts();

	private final Map<String, Step<?, ?>> operations;

	private final Map<String, String> selections;

	Map<String, String> selections() {
		return selections;
	}

	/**
	 * Validate the supplied deployment against every immutable definition contract
	 * without executing application steps. Entry IDs select exact registered instances.
	 * Full generic Types, not erased classes or object-supplied hints, must agree with
	 * the definition.
	 * @throws WorkflowRefusal for changed codecs/types, unavailable registrations or a
	 * supplied Step declaration that differs from the selected executable contract
	 */
	WorkflowExecutionBindings(StepRegistry registry, ExecutionCompatibility compatibility, ValidatedWorkflow workflow) {
		if (!codec.identity().equals(workflow.codecIdentity())
				|| !codec.identity().equals(compatibility.manifest().codec())) {
			throw new WorkflowRefusal("CODEC_CHANGED", "deployed codec contract differs");
		}
		Map<String, Step<?, ?>> selectedObjects = new HashMap<>();
		Map<String, String> selectedNames = new java.util.TreeMap<>();
		try {
			for (var value : workflow.values().values()) {
				if (!codec.contract(value.declaration()).equals(value.contract()))
					throw new WorkflowRefusal("TYPE_CHANGED", "declared type/shape/codec differs: " + value.identity());
			}
			for (var association : workflow.suppliedSteps().entrySet()) {
				String nodeId = association.getKey().graphName();
				var node = workflow.graph().nodeByName(nodeId);
				if (!(node instanceof WorkflowNode.StepNode requirement))
					throw new WorkflowRefusal("STEP_CONTRACT", "selection does not address an operation: " + nodeId);
				Step<?, ?> step = association.getValue();
				String name = registry.nameOf(step);
				StepTypes actual = StepTypes.of(step.getClass());
				if (!actual.input().equals(requirement.input()) || !actual.output().equals(requirement.output()))
					throw new WorkflowRefusal("STEP_CONTRACT", "supplied Step contract differs: " + name);
				selectedObjects.put(nodeId, step);
				selectedNames.put(nodeId, name);
			}
			var required = workflow.graph()
				.nodes()
				.stream()
				.filter(WorkflowNode.StepNode.class::isInstance)
				.map(n -> n.name())
				.collect(java.util.stream.Collectors.toSet());
			if (!required.equals(selectedNames.keySet()))
				throw new WorkflowRefusal("STEP_CONTRACT", "missing or extraneous prepared node selections");
		}
		catch (LinkageError ex) {
			throw new WorkflowRefusal("STEP_UNAVAILABLE", "deployed operation/type resolution failed", ex);
		}
		catch (IllegalArgumentException ex) {
			throw new WorkflowRefusal("TYPE_CHANGED", "deployed type contract refused", ex);
		}
		operations = Map.copyOf(selectedObjects);
		selections = java.util.Collections.unmodifiableMap(selectedNames);
	}

	/**
	 * Reconstruct an exact typed value. Admission uses this to round-trip the freshly
	 * encoded root before it is saved. For recovered values, DurableWorkflows first
	 * checks saved identity, digest, type/codec and provenance. The strict codec checks
	 * lossless reconstruction in both cases, including concrete generic element types.
	 * @throws WorkflowRefusal with DECODE_FAILED if bytes cannot satisfy the declaration
	 */
	Object decode(byte[] bytes, Type declaration) {
		try {
			return codec.decodeBytes(bytes, declaration);
		}
		catch (IllegalArgumentException | LinkageError ex) {
			throw new WorkflowRefusal("DECODE_FAILED", "saved value cannot decode losslessly", ex);
		}
	}

	/**
	 * Check an application value against its complete declared Type and encode a durable
	 * snapshot. Invalid values, nulls or lossy reconstruction refuse before result
	 * acceptance.
	 * @throws WorkflowRefusal with ENCODE_FAILED for a rejected value or codec failure
	 */
	byte[] encode(Object value, Type declaration) {
		try {
			return codec.encodeBytes(value, declaration);
		}
		catch (IllegalArgumentException | LinkageError ex) {
			throw new WorkflowRefusal("ENCODE_FAILED", "value cannot encode losslessly", ex);
		}
	}

	/**
	 * Construct a new input record from the validated recipe's ordered components.
	 * RegionAnalyzer resolves record components in declaration order, substituting
	 * concrete record type arguments; ValidatedWorkflow retains that order in
	 * ValueRecipe. The runtime verifies and decodes each referenced value in that same
	 * order before calling here.
	 * <p>
	 * The canonical constructor is located using getRecordComponents() in declaration
	 * order. Its reflection parameter Classes are erased, but each value has already been
	 * decoded against its full selected Type; the assembled record is then encoded and
	 * losslessly checked before the attempt is charged. Constructor execution can occur
	 * inside the preparation transaction: this is value construction, not Step.execute.
	 * @param declaration the selected record input Type, possibly parameterized
	 * @param components decoded values in canonical constructor order
	 * @return the assembled record, to be saved as an immutable effective input
	 * @throws WorkflowRefusal for a non-record recipe, inaccessible constructor or
	 * constructor failure; no application Step has been entered at this point
	 */
	Object assemble(Type declaration, List<Object> components) {
		Class<?> raw = declaration instanceof Class<?> c ? c
				: (Class<?>) ((ParameterizedType) declaration).getRawType();
		if (!raw.isRecord())
			throw new WorkflowRefusal("BINDING_INVALID", "assembly requires a declared record");
		try {
			Class<?>[] parameters = Arrays.stream(raw.getRecordComponents())
				.map(RecordComponent::getType)
				.toArray(Class<?>[]::new);
			return raw.getConstructor(parameters).newInstance(components.toArray());
		}
		catch (ReflectiveOperationException ex) {
			throw new WorkflowRefusal("INPUT_FAILED", "record input construction failed", unwrap(ex));
		}
	}

	/**
	 * Invoke the exact checked instance on this calling thread, outside persistence
	 * transactions. The unchecked cast is the single bridge over Java's erased dispatch:
	 * the constructor has compared StepTypes.of(actualClass) with both selected full
	 * Types. DurableWorkflows has verified the chosen input recipe/provenance and decoded
	 * its bytes against that Type before charging and checking this attempt. This method
	 * does not choose a value by its runtime class or use a mutable context lookup.
	 * @param entry graph node ID whose supplied implementation was frozen at preparation
	 * @param input decoded effective input for that recipe
	 * @param context metadata for the already charged physical attempt
	 * @return the provisional application result; encoding/acceptance happens separately
	 * @throws RuntimeException an application failure, handled as STEP_FAILED by the
	 * runtime
	 */
	@SuppressWarnings("unchecked") // Concrete generic declarations were checked against
									// compiler selections above.
	Object execute(String entry, Object input, StepContext context) {
		return ((Step<Object, Object>) operations.get(entry)).execute(context, input);
	}

	private static Throwable unwrap(Throwable ex) {
		return ex instanceof InvocationTargetException call && call.getCause() != null ? call.getCause() : ex;
	}

}
