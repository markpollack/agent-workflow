package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;
import java.util.List;

/**
 * Detached, immutable durable facts returned by start/advance/resume/inspect. This is a
 * view of one committed transition, not a live execution handle: another caller can later
 * cancel or advance the run. No thread, lease or executable object is represented.
 * <p>
 * currentNode is a derived root-scope position; scopes expose nested progress. ACTIVE
 * means unfinished, not necessarily currently executing. A normally interrupted resume
 * can return ACTIVE. reason is null while active; terminal outcomes include a reason.
 * Lists and byte payloads are defensive copies, never writable store handles.
 */
public record RunSnapshot(String runId, String displayName, Status status, Instant admittedAt, Instant deadline,
		String deadlineOrigin, String authoredIdentity, ExecutionCompatibility.Manifest deployment, String currentNode,
		java.util.Map<String, String> selectedSteps, Reason reason, List<Invocation> invocations, List<Value> values,
		List<Event> events, String rootScope, String rootDefinition, long logicalInvocations, List<Scope> scopes,
		List<ReturnReceipt> returns, java.util.Map<String, DefinitionSelection> definitions, Resources resources) {
	public enum Status {

		ACTIVE, SUCCEEDED, FAILED, CANCELLED

	}

	public record Reason(String code, String message, String actor, Instant time) {
	}

	/**
	 * One logical placement's exact input/output value references and physical attempts.
	 * An unresolved invocation may acquire another attempt after a crash; it retains its
	 * ID.
	 */
	public record Invocation(String invocationId, String placement, String inputValue, String outputValue,
			String status, List<Attempt> attempts, String scope, String kind, String childScope, String localOutcome) {
		public Invocation {
			attempts = List.copyOf(attempts);
		}
	}

	/**
	 * One charged physical entry. Charging commits before application code; UNRESOLVED
	 * does not prove whether external work occurred. COMMITTED identifies an accepted
	 * result.
	 */
	public record Attempt(String attemptId, int number, Instant chargedAt, String disposition) {
	}

	/**
	 * Exact saved payload plus selected type/codec, digest and producer/component
	 * provenance. These references describe how the value was selected, not instructions
	 * to reconstruct it.
	 */
	public record Value(String valueId, String declaredType, String shapeDigest, String codec, String payloadDigest,
			byte[] payload, List<String> components, List<String> consumed, String producerInvocation, String scope,
			String definition, String sourceValue) {
		public Value {
			payload = payload.clone();
			components = List.copyOf(components);
			consumed = List.copyOf(consumed);
		}

		@Override
		public byte[] payload() {
			return payload.clone();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Value v && valueId.equals(v.valueId) && declaredType.equals(v.declaredType)
					&& shapeDigest.equals(v.shapeDigest) && codec.equals(v.codec)
					&& payloadDigest.equals(v.payloadDigest) && java.util.Arrays.equals(payload, v.payload)
					&& components.equals(v.components) && consumed.equals(v.consumed)
					&& producerInvocation.equals(v.producerInvocation) && scope.equals(v.scope)
					&& definition.equals(v.definition) && sourceValue.equals(v.sourceValue);
		}

		@Override
		public int hashCode() {
			return 31
					* java.util.Objects.hash(valueId, declaredType, shapeDigest, codec, payloadDigest, components,
							consumed, producerInvocation, scope, definition, sourceValue)
					+ java.util.Arrays.hashCode(payload);
		}
	}

	public record Event(long sequence, String kind, Instant time, String detail) {
	}

	/**
	 * Exact local registration and callee selections, keyed by the prepared definition
	 * ID.
	 */
	public record DefinitionSelection(String authoredIdentity, java.util.Map<String, String> leaves,
			java.util.Map<String, String> callees) {
		public DefinitionSelection {
			leaves = java.util.Map.copyOf(leaves);
			callees = java.util.Map.copyOf(callees);
		}
	}

	/**
	 * Admitted policy and conservative structural bounds; counts do not promise capacity.
	 */
	public record Resources(int maximumAttempts, int maximumDepth, long maximumInvocations, long leafBound,
			long compositeBound, int depthBound, long logicalBound, long scopeBound, long attemptBound) {
	}

	/**
	 * A scope's accepted local outcome remains visible even if an ancestor later revokes
	 * its return.
	 */
	public record LocalOutcome(String id, String status, Reason reason, String source, String successValue) {
	}

	public record Revocation(String code, String ancestorScope, Instant time) {
	}

	public record NodeProgress(String node, String phase, String invocation, String settlement) {
	}

	public record ReturnReceipt(String id, String invocation, String childScope, String localOutcome,
			String outputValue, Instant time) {
	}

	public record Scope(String id, String definition, String parentScope, String openingInvocation, String returnNode,
			String inputValue, int depth, Instant openedAt, Instant deadline, String deadlineOrigin, String lifecycle,
			LocalOutcome localOutcome, Revocation revocation, List<NodeProgress> nodes) {
		public Scope {
			nodes = List.copyOf(nodes);
		}
	}

	public RunSnapshot {
		selectedSteps = java.util.Map.copyOf(selectedSteps);
		invocations = List.copyOf(invocations);
		values = List.copyOf(values);
		events = List.copyOf(events);
		scopes = List.copyOf(scopes);
		returns = List.copyOf(returns);
		definitions = java.util.Map.copyOf(definitions);
	}

	public boolean terminal() {
		return status != Status.ACTIVE;
	}
}
