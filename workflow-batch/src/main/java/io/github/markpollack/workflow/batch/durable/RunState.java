package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;
import java.util.*;

/**
 * Mutable format-4 aggregate owned by one JdbcRunStore.Tx. Values, logical invocations,
 * physical attempts, continuation and events serialize together; public fields support
 * the store codec and are not an application API. Never retain this object across
 * transactions. snapshot() creates the detached inspection model returned to callers.
 */
final class RunState {

	public int format = 4;

	public String id, key, admission, compatibility, display, authored, deadlineOrigin;

	public ApplicationDeployment.Manifest deployment;

	public long admitted, deadline, revision;

	public String status = "ACTIVE", reasonCode = "", reasonMessage = "", actor = "", output = "";

	public long terminalAt;

	public int next, maximumAttempts;

	public Map<String, Value> values = new LinkedHashMap<>();

	public List<Invocation> invocations = new ArrayList<>();

	public List<Event> events = new ArrayList<>();

	public static final class Value {

		public String id, type, shape, codec, digest, producer;

		public byte[] payload;

		public List<String> components = new ArrayList<>(), consumed = new ArrayList<>();

		RunSnapshot.Value snapshot() {
			return new RunSnapshot.Value(id, type, shape, codec, digest, payload, components, consumed, producer);
		}

	}

	public static final class Invocation {

		public String id, placement, input, output, status = "UNRESOLVED";

		public List<Attempt> attempts = new ArrayList<>();

		RunSnapshot.Invocation snapshot() {
			return new RunSnapshot.Invocation(id, placement, input, output, status,
					attempts.stream().map(Attempt::snapshot).toList());
		}

	}

	public static final class Attempt {

		public String id, disposition = "UNRESOLVED";

		public int number;

		public long charged;

		RunSnapshot.Attempt snapshot() {
			return new RunSnapshot.Attempt(id, number, Instant.ofEpochMilli(charged), disposition);
		}

	}

	public static final class Event {

		public long sequence, time;

		public String kind, detail;

		RunSnapshot.Event snapshot() {
			return new RunSnapshot.Event(sequence, kind, Instant.ofEpochMilli(time), detail);
		}

	}

	void event(String kind, long time, String detail) {
		Event e = new Event();
		e.sequence = events.size() + 1L;
		e.time = time;
		e.kind = kind;
		e.detail = detail;
		events.add(e);
	}

	boolean active() {
		return status.equals("ACTIVE");
	}

	void terminal(String outcome, String code, String message, String actor, long now) {
		if (!active())
			return;
		status = outcome;
		reasonCode = code;
		reasonMessage = message;
		this.actor = actor;
		terminalAt = now;
		for (Invocation call : invocations)
			if (call.status.equals("UNRESOLVED")) {
				call.status = outcome;
				for (Attempt attempt : call.attempts)
					if (attempt.disposition.equals("UNRESOLVED"))
						attempt.disposition = code;
			}
		event(outcome, now, code + ":" + message);
	}

	/**
	 * Copy saved facts into immutable inspection values; payloads and collections are
	 * defensively copied.
	 */
	RunSnapshot snapshot() {
		return new RunSnapshot(id, display, RunSnapshot.Status.valueOf(status), Instant.ofEpochMilli(admitted),
				Instant.ofEpochMilli(deadline), deadlineOrigin, authored, deployment, next,
				active() ? null
						: new RunSnapshot.Reason(reasonCode, reasonMessage, actor, Instant.ofEpochMilli(terminalAt)),
				invocations.stream().map(Invocation::snapshot).toList(),
				values.values().stream().map(Value::snapshot).toList(), events.stream().map(Event::snapshot).toList());
	}

}
