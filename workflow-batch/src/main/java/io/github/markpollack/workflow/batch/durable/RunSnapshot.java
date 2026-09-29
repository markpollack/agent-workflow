package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;
import java.util.List;

/**
 * Detached, immutable durable facts returned by start/advance/resume/inspect. This is a
 * view of one committed transition, not a live execution handle: another caller can later
 * cancel or advance the run. No thread, lease or executable object is represented.
 * <p>
 * currentNode is the durable graph continuation (including an explicit terminal). ACTIVE
 * means unfinished, not necessarily currently executing. A normally interrupted resume
 * can return ACTIVE. reason is null while active; terminal outcomes include a reason.
 * Lists and byte payloads are defensive copies, never writable store handles.
 */
public record RunSnapshot(String runId, String displayName, Status status, Instant admittedAt, Instant deadline,
		String deadlineOrigin, String authoredIdentity, ExecutionCompatibility.Manifest deployment, String currentNode,
		java.util.Map<String, String> selectedSteps, Reason reason, List<Invocation> invocations, List<Value> values,
		List<Event> events) {
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
			String status, List<Attempt> attempts) {
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
			byte[] payload, List<String> components, List<String> consumed, String producerInvocation) {
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
					&& producerInvocation.equals(v.producerInvocation);
		}

		@Override
		public int hashCode() {
			return 31 * java.util.Objects.hash(valueId, declaredType, shapeDigest, codec, payloadDigest, components,
					consumed, producerInvocation) + java.util.Arrays.hashCode(payload);
		}
	}

	public record Event(long sequence, String kind, Instant time, String detail) {
	}

	public RunSnapshot {
		selectedSteps = java.util.Map.copyOf(selectedSteps);
		invocations = List.copyOf(invocations);
		values = List.copyOf(values);
		events = List.copyOf(events);
	}

	public boolean terminal() {
		return status != Status.ACTIVE;
	}
}
