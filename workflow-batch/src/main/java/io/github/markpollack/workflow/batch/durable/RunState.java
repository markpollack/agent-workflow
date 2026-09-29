package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;
import java.util.*;

/**
 * Format-6 aggregate owned by one JdbcRunStore.Tx. Scopes hold graph progress;
 * invocation, attempt, value, outcome and return records retain facts. No field is a Java
 * call stack. Public fields serve the store codec only. Never retain this mutable object
 * across TXs.
 */
final class RunState {

	public int format = 6;

	public String id, key, admission, compatibility, display, authored, deadlineOrigin;

	public ExecutionCompatibility.Manifest deployment;

	public long admitted, deadline, revision;

	public String status = "ACTIVE", reasonCode = "", reasonMessage = "", actor = "", output = "";

	public long terminalAt;

	public String rootScope, rootDefinition;

	public Map<String, DefinitionDescriptor> definitions = new LinkedHashMap<>();

	public int maximumAttempts, maximumDepth;

	public long maximumInvocations, logicalCount;

	public CompositionBounds bounds;

	public Map<String, Scope> scopes = new LinkedHashMap<>();

	public Map<String, Value> values = new LinkedHashMap<>();

	public List<Invocation> invocations = new ArrayList<>();

	public Map<String, Return> returns = new LinkedHashMap<>();

	public List<Event> events = new ArrayList<>();

	public static final class Scope {

		public String id, definition, parent = "", opening = "", returnNode = "", input;

		public int depth;

		public long opened, deadline;

		public String deadlineOrigin, lifecycle = "OPEN";

		public Outcome localOutcome;

		public Revocation revocation;

		public Map<String, Node> nodes = new LinkedHashMap<>();

		boolean open() {
			return "OPEN".equals(lifecycle);
		}

		Node ready(String node) {
			Node progress = new Node();
			progress.node = node;
			if (nodes.putIfAbsent(node, progress) != null)
				throw new WorkflowRefusal("PROGRESS_INVALID", "node cannot become ready twice: " + node);
			return progress;
		}

	}

	public static final class Node {

		public String node, phase = "READY", invocation = "", settlement = "";

	}

	public record Outcome(String id, String status, String code, String message, String actor, String source,
			long acceptedAt, String successValue) {
	}

	public record Revocation(String code, String ancestor, long time) {
	}

	public record Return(String id, String invocation, String child, String outcome, String output, long time) {
	}

	public static final class Value {

		public String id, scope, definition, local, type, shape, codec, digest, producer, source = "";

		public byte[] payload;

		public List<String> components = new ArrayList<>(), consumed = new ArrayList<>();

		RunSnapshot.Value snapshot() {
			return new RunSnapshot.Value(id, type, shape, codec, digest, payload, components, consumed, producer, scope,
					definition, source);
		}

	}

	public static final class Invocation {

		public String id, scope, placement, kind, input, output, status = "UNRESOLVED", outcome = "", child = "";

		public List<Attempt> attempts = new ArrayList<>();

		RunSnapshot.Invocation snapshot() {
			return new RunSnapshot.Invocation(id, placement, input, output, status,
					attempts.stream().map(Attempt::snapshot).toList(), scope, kind, child, outcome);
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

	/**
	 * Accept a local outcome once; revoke unresolved work without erasing accepted facts.
	 */
	void complete(Scope scope, String status, String code, String message, String actor, String source,
			String successValue, long now) {
		if (!scope.open())
			return;
		scope.localOutcome = new Outcome(ScopeIds.outcome(scope.id), status, code, message, actor, source, now,
				successValue);
		scope.lifecycle = "LOCAL_TERMINAL";
		revokePending(scope, code);
		for (Scope descendant : scopes.values()) {
			if (!descendant.id.equals(scope.id) && descendantOf(descendant, scope.id)
					&& !"RETURNED".equals(descendant.lifecycle) && !"REVOKED".equals(descendant.lifecycle)) {
				descendant.lifecycle = "REVOKED";
				descendant.revocation = new Revocation(code, scope.id, now);
				revokePending(descendant, code);
			}
		}
		event("LOCAL_OUTCOME", now, scope.id + ":" + status + ":" + code);
		if (scope.id.equals(rootScope)) {
			this.status = status;
			reasonCode = code;
			reasonMessage = message;
			this.actor = actor;
			terminalAt = now;
			output = successValue;
			event(status, now, code + ":" + message);
		}
	}

	private boolean descendantOf(Scope scope, String ancestor) {
		Set<String> seen = new HashSet<>();
		String parent = scope.parent;
		while (!parent.isEmpty()) {
			if (!seen.add(parent) || !scopes.containsKey(parent))
				throw new WorkflowRefusal("PROGRESS_INVALID", "invalid scope ancestry");
			if (parent.equals(ancestor))
				return true;
			parent = scopes.get(parent).parent;
		}
		return false;
	}

	private void revokePending(Scope scope, String code) {
		for (Node node : scope.nodes.values()) {
			if (!"SETTLED".equals(node.phase))
				node.phase = "REVOKED";
		}
		for (Invocation call : invocations) {
			if (call.scope.equals(scope.id) && call.status.equals("UNRESOLVED")) {
				call.status = "REVOKED";
				for (Attempt attempt : call.attempts)
					if (attempt.disposition.equals("UNRESOLVED"))
						attempt.disposition = code;
			}
		}
	}

	void terminal(String status, String code, String message, String actor, long now) {
		if (active())
			complete(scopes.get(rootScope), status, code, message, actor, "runtime", "", now);
	}

	/**
	 * Root first, then outermost expired OPEN scope; completed local outcomes never
	 * expire.
	 */
	boolean observe(long now) {
		if (!active())
			return false;
		Scope expired = scopes.values()
			.stream()
			.filter(Scope::open)
			.filter(s -> now >= s.deadline)
			.min(Comparator.comparingInt(s -> s.depth))
			.orElse(null);
		if (expired == null)
			return false;
		complete(expired, "FAILED", "DEADLINE_EXCEEDED", "absolute deadline reached", "store", "runtime", "", now);
		return true;
	}

	RunSnapshot snapshot() {
		Map<String, String> selected = new TreeMap<>();
		definitions.forEach(
				(d, descriptor) -> descriptor.leaves().forEach((p, name) -> selected.put(Digests.fields(d, p), name)));
		List<RunSnapshot.Scope> scopeViews = scopes.values()
			.stream()
			.map(s -> new RunSnapshot.Scope(s.id, s.definition, s.parent, s.opening, s.returnNode, s.input, s.depth,
					Instant.ofEpochMilli(s.opened), Instant.ofEpochMilli(s.deadline), s.deadlineOrigin, s.lifecycle,
					s.localOutcome == null ? null
							: new RunSnapshot.LocalOutcome(s.localOutcome.id(), s.localOutcome.status(),
									new RunSnapshot.Reason(s.localOutcome.code(), s.localOutcome.message(),
											s.localOutcome.actor(), Instant.ofEpochMilli(s.localOutcome.acceptedAt())),
									s.localOutcome.source(), s.localOutcome.successValue()),
					s.revocation == null ? null
							: new RunSnapshot.Revocation(s.revocation.code(), s.revocation.ancestor(),
									Instant.ofEpochMilli(s.revocation.time())),
					s.nodes.values()
						.stream()
						.map(n -> new RunSnapshot.NodeProgress(n.node, n.phase, n.invocation, n.settlement))
						.toList()))
			.toList();
		Scope root = scopes.get(rootScope);
		String current = root.nodes.values()
			.stream()
			.filter(n -> !"SETTLED".equals(n.phase))
			.map(n -> n.node)
			.findFirst()
			.orElseGet(() -> root.localOutcome == null ? "" : root.localOutcome.source());
		if (root.localOutcome != null && !root.nodes.containsKey(current)) {
			String source = current;
			current = invocations.stream()
				.filter(i -> i.id.equals(source) || i.outcome.equals(source))
				.filter(i -> i.scope.equals(rootScope))
				.map(i -> i.placement)
				.findFirst()
				.orElse(current);
		}
		return new RunSnapshot(id, display, RunSnapshot.Status.valueOf(status), Instant.ofEpochMilli(admitted),
				Instant.ofEpochMilli(deadline), deadlineOrigin, authored, deployment, current, selected,
				active() ? null
						: new RunSnapshot.Reason(reasonCode, reasonMessage, actor, Instant.ofEpochMilli(terminalAt)),
				invocations.stream().map(Invocation::snapshot).toList(),
				values.values().stream().map(Value::snapshot).toList(), events.stream().map(Event::snapshot).toList(),
				rootScope, rootDefinition, logicalCount, scopeViews,
				returns.values()
					.stream()
					.map(r -> new RunSnapshot.ReturnReceipt(r.id(), r.invocation(), r.child(), r.outcome(), r.output(),
							Instant.ofEpochMilli(r.time())))
					.toList(),
				definitions.entrySet()
					.stream()
					.collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
							e -> new RunSnapshot.DefinitionSelection(e.getValue().authored(), e.getValue().leaves(),
									e.getValue().callees()))),
				new RunSnapshot.Resources(maximumAttempts, maximumDepth, maximumInvocations, bounds.leaves(),
						bounds.composites(), bounds.depth(), bounds.logical(), bounds.scopes(), bounds.attempts()));
	}

}
