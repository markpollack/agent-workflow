package io.github.markpollack.workflow.batch.durable;

import java.util.*;

/**
 * Checks saved relations independently of compatibility digests and executable objects.
 */
final class RunIntegrity {

	private RunIntegrity() {
	}

	static void validate(RunState run) {
		try {
			check(run);
		}
		catch (WorkflowRefusal ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new WorkflowRefusal("PROGRESS_INVALID", "malformed saved progress", ex);
		}
	}

	private static void check(RunState r) {
		require(r.format == 6 && r.id != null && r.deployment != null, "run envelope");
		require(Set.of("ACTIVE", "SUCCEEDED", "FAILED", "CANCELLED").contains(r.status), "run status");
		var policy = new ExecutionPolicy(r.maximumAttempts, r.maximumDepth, r.maximumInvocations);
		require(closureBounds(r, policy).equals(r.bounds), "admitted structural bounds");
		require(r.logicalCount == r.invocations.size() && r.logicalCount <= r.bounds.logical(), "logical counter");
		require(r.scopes.size() <= r.bounds.scopes(), "scope bound");
		require(ScopeIds.root(r.id).equals(r.rootScope), "root scope identity");
		RunState.Scope root = r.scopes.get(r.rootScope);
		require(root != null && root.parent.isEmpty() && root.opening.isEmpty() && root.returnNode.isEmpty()
				&& root.depth == 0 && root.definition.equals(r.rootDefinition), "one root scope");
		require(root.opened == r.admitted && root.deadline == r.deadline
				&& root.deadlineOrigin.equals(r.deadlineOrigin), "root deadline facts");
		require(r.definitions.get(r.rootDefinition).authored().equals(r.authored), "root authored identity");
		Map<String, RunState.Invocation> calls = new HashMap<>();
		Set<String> attempts = new HashSet<>();
		for (var call : r.invocations) {
			require(calls.put(call.id, call) == null, "duplicate invocation");
			var scope = r.scopes.get(call.scope);
			require(scope != null && ScopeIds.invocation(r.id, call.scope, call.placement).equals(call.id),
					"invocation identity/scope");
			var node = scope.nodes.get(call.placement);
			require(node != null && node.invocation.equals(call.id), "invocation node link");
			require(Set.of("UNRESOLVED", "SETTLED", "REVOKED").contains(call.status), "invocation state");
			if (!r.values.containsKey(call.input))
				throw new WorkflowRefusal("VALUE_MISSING", "invocation exact input missing");
			require(call.attempts.size() <= r.maximumAttempts, "attempt allowance");
			int number = 0;
			for (var attempt : call.attempts) {
				require(attempt.id != null && attempts.add(attempt.id) && attempt.number == ++number
						&& attempt.charged >= scope.opened, "attempt identity/order/time");
				if (number < call.attempts.size())
					require("REATTEMPTED".equals(attempt.disposition), "earlier attempt disposition");
			}
			var descriptor = r.definitions.get(scope.definition);
			if (call.kind.equals("LEAF")) {
				require(descriptor.leaves().containsKey(call.placement) && call.child.isEmpty()
						&& !call.attempts.isEmpty(), "leaf selection/attempt");
				String disposition = call.attempts.getLast().disposition;
				require(call.status.equals("UNRESOLVED") == disposition.equals("UNRESOLVED"),
						"current attempt disposition");
				if (call.status.equals("SETTLED")) {
					require(!call.outcome.isEmpty() && call.outcome.equals(disposition), "leaf settlement disposition");
					require(node.phase.equals("SETTLED") && node.settlement.equals(call.id), "leaf node settlement");
					require(!call.outcome.equals("COMMITTED") || r.values.containsKey(call.output),
							"accepted leaf output");
				}
			}
			else {
				require(call.kind.equals("COMPOSITE") && call.attempts.isEmpty(), "composite has no physical attempts");
				var child = r.scopes.get(call.child);
				require(child != null && child.parent.equals(scope.id) && child.opening.equals(call.id)
						&& child.id.equals(ScopeIds.child(scope, call.placement))
						&& child.definition.equals(descriptor.callees().get(call.placement)),
						"composite scope selection");
				var receipt = r.returns.get(call.id);
				if (call.status.equals("SETTLED")) {
					require(child.lifecycle.equals("RETURNED") && receipt != null && child.localOutcome != null,
							"returned child receipt");
					require(call.outcome.equals(child.localOutcome.id()) && receipt.outcome().equals(call.outcome)
							&& receipt.id().equals(ScopeIds.receipt(call.id)) && receipt.invocation().equals(call.id)
							&& receipt.child().equals(child.id) && receipt.time() >= child.localOutcome.acceptedAt(),
							"return identity/outcome");
					require(node.phase.equals("SETTLED") && node.settlement.equals(receipt.id()),
							"composite node settlement");
					boolean success = child.localOutcome.status().equals("SUCCEEDED");
					require(receipt.output().equals(success ? call.output : ""), "return output reference");
					if (success) {
						var value = r.values.get(call.output);
						require(value != null && value.source.equals(child.localOutcome.successValue()),
								"return value provenance");
					}
				}
				else {
					require(receipt == null && call.outcome.isEmpty(), "unreturned invocation has receipt");
					require(call.status.equals("REVOKED") ? child.lifecycle.equals("REVOKED")
							: Set.of("OPEN", "LOCAL_TERMINAL").contains(child.lifecycle), "pending child lifecycle");
				}
			}
			if (call.status.equals("REVOKED"))
				require(node.phase.equals("REVOKED"), "revoked invocation phase");
			if (call.status.equals("UNRESOLVED"))
				require(scope.open() && node.phase.equals(call.kind.equals("LEAF") ? "ENTERED" : "WAITING_CHILD"),
						"unresolved invocation authority");
		}
		require(attempts.size() <= r.bounds.attempts(), "physical attempt bound");
		require(r.scopes.size() == 1 + r.invocations.stream().filter(c -> c.kind.equals("COMPOSITE")).count(),
				"scope counter");
		require(r.returns.size() == r.invocations.stream()
			.filter(c -> c.kind.equals("COMPOSITE") && c.status.equals("SETTLED"))
			.count(), "extra return receipt");
		for (var entry : r.scopes.entrySet())
			checkScope(r, entry.getKey(), entry.getValue(), calls);
		for (var entry : r.values.entrySet()) {
			var v = entry.getValue();
			var scope = r.scopes.get(v.scope);
			require(scope != null && scope.definition.equals(v.definition) && v.id.equals(entry.getKey())
					&& v.id.equals(ScopeIds.value(r.id, v.scope, v.definition, v.local)), "value scope/identity");
			if (!v.digest.equals(Digests.of(v.payload)))
				throw new WorkflowRefusal("VALUE_CHANGED", "saved payload digest differs");
			if (!(v.producer.isEmpty() ? scope.id.equals(r.rootScope) && v.id.equals(scope.input)
					: calls.containsKey(v.producer)))
				throw new WorkflowRefusal("VALUE_CHANGED", "value producer differs");
			for (String source : v.components)
				require(r.values.containsKey(source), "value component missing");
			for (String source : v.consumed)
				require(r.values.containsKey(source), "consumed value missing");
			if (!v.producer.isEmpty() && scope.id.equals(calls.get(v.producer).scope)) {
				var producer = calls.get(v.producer);
				require(v.id.equals(producer.input) || v.id.equals(producer.output), "value is not an invocation fact");
				if (v.id.equals(producer.output))
					require(producer.status.equals("SETTLED") && (producer.kind.equals("COMPOSITE")
							? r.scopes.get(producer.child).localOutcome.status().equals("SUCCEEDED")
							: producer.outcome.equals("COMMITTED")), "output has no accepted successful producer");
			}
			if (!v.source.isEmpty()) {
				var source = r.values.get(v.source);
				require(source != null && Arrays.equals(source.payload, v.payload) && source.type.equals(v.type)
						&& source.shape.equals(v.shape) && source.codec.equals(v.codec),
						"boundary snapshot/source differs");
			}
		}
		require(root.open() == r.active(), "root/run outcome disagreement");
		if (!r.active()) {
			var o = root.localOutcome;
			require(o != null && root.lifecycle.equals("LOCAL_TERMINAL") && o.status().equals(r.status)
					&& o.code().equals(r.reasonCode) && o.message().equals(r.reasonMessage) && o.actor().equals(r.actor)
					&& o.acceptedAt() == r.terminalAt && o.successValue().equals(r.output), "root terminal facts");
		}
		long sequence = 0, time = r.admitted;
		for (var e : r.events) {
			require(e.sequence == ++sequence && e.time >= time, "event sequence/time");
			time = e.time;
		}
	}

	private static void checkScope(RunState r, String key, RunState.Scope s, Map<String, RunState.Invocation> calls) {
		require(s.id.equals(key) && r.definitions.containsKey(s.definition) && r.values.containsKey(s.input),
				"scope identity/input");
		require(s.deadline > s.opened && s.depth <= r.maximumDepth, "scope deadline/depth");
		require(Set.of("OPEN", "LOCAL_TERMINAL", "RETURNED", "REVOKED").contains(s.lifecycle), "scope lifecycle");
		if (!s.id.equals(r.rootScope)) {
			var parent = r.scopes.get(s.parent);
			var call = calls.get(s.opening);
			require(parent != null && parent.depth + 1 == s.depth && s.deadline <= parent.deadline
					&& s.opened >= parent.opened && call != null && call.child.equals(s.id), "scope ancestry/opening");
			var value = r.values.get(s.input);
			require(value.producer.equals(call.id) && value.source.equals(call.input), "scope input provenance");
			if (s.open() || s.lifecycle.equals("LOCAL_TERMINAL"))
				require(parent.open(), "live child under closed parent");
		}
		if (s.open())
			require(s.localOutcome == null && s.revocation == null, "OPEN scope outcome/revocation");
		if (s.lifecycle.equals("LOCAL_TERMINAL") || s.lifecycle.equals("RETURNED"))
			require(s.localOutcome != null && s.revocation == null, "closed scope outcome/revocation");
		if (s.lifecycle.equals("REVOKED")) {
			require(s.revocation != null && r.scopes.containsKey(s.revocation.ancestor())
					&& r.scopes.get(s.revocation.ancestor()).localOutcome != null, "revocation authority");
		}
		if (s.localOutcome != null) {
			var o = s.localOutcome;
			require(o.id().equals(ScopeIds.outcome(s.id))
					&& Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(o.status()) && o.acceptedAt() >= s.opened,
					"local outcome identity/status/time");
			require(o.status().equals("SUCCEEDED") ? r.values.containsKey(o.successValue())
					: o.successValue().isEmpty(), "local result");
			if (o.actor().equals("workflow")) {
				var terminal = s.nodes.get(o.source());
				require(terminal != null && terminal.phase.equals("SETTLED") && terminal.invocation.isEmpty()
						&& terminal.settlement.equals(o.id()) && o.acceptedAt() < s.deadline, "folded terminal fact");
			}
		}
		long frontiers = 0;
		for (var entry : s.nodes.entrySet()) {
			var n = entry.getValue();
			var descriptor = r.definitions.get(s.definition);
			boolean callNode = descriptor.leaves().containsKey(n.node) || descriptor.callees().containsKey(n.node);
			require(callNode || (s.localOutcome != null && s.localOutcome.actor().equals("workflow")
					&& s.localOutcome.source().equals(n.node)), "node outside selected definition");
			if (callNode && n.phase.equals("SETTLED"))
				require(!n.invocation.isEmpty(), "settled call without invocation");
			require(n.node.equals(entry.getKey())
					&& Set.of("READY", "ENTERED", "WAITING_CHILD", "SETTLED", "REVOKED").contains(n.phase),
					"node identity/phase");
			if (Set.of("READY", "ENTERED", "WAITING_CHILD").contains(n.phase)) {
				frontiers++;
				require(s.open(), "frontier in closed scope");
			}
			if (n.phase.equals("READY"))
				require(n.invocation.isEmpty() && n.settlement.isEmpty(), "READY has prior facts");
			if (!n.invocation.isEmpty())
				require(calls.containsKey(n.invocation) && calls.get(n.invocation).scope.equals(s.id)
						&& calls.get(n.invocation).placement.equals(n.node), "node invocation link");
		}
		require(frontiers <= 1, "multiple frontiers");
		if (s.open() && frontiers == 0)
			throw new WorkflowRefusal("STRANDED_PROGRESS", "no frontier for " + r.id + "/" + s.id);
		if (!s.open())
			require(frontiers == 0, "closed scope frontier");
	}

	private static CompositionBounds closureBounds(RunState r, ExecutionPolicy policy) {
		Map<String, CompositionBounds> done = new HashMap<>();
		Set<String> active = new HashSet<>();
		record Visit(String key, boolean exit) {
		}
		Deque<Visit> todo = new ArrayDeque<>();
		todo.push(new Visit(r.rootDefinition, false));
		while (!todo.isEmpty()) {
			Visit v = todo.pop();
			if (done.containsKey(v.key()))
				continue;
			var d = r.definitions.get(v.key());
			require(d != null && d.identity().equals(v.key()), "descriptor identity/closure");
			if (!v.exit()) {
				require(active.add(v.key()), "cyclic descriptor closure");
				todo.push(new Visit(v.key(), true));
				d.callees().values().forEach(k -> todo.push(new Visit(k, false)));
			}
			else {
				require(Collections.disjoint(d.leaves().keySet(), d.callees().keySet()),
						"overlapping leaf/composite selection");
				done.put(v.key(), CompositionBounds.combine(d.leaves().size(),
						d.callees().values().stream().map(done::get).toList(), policy));
				active.remove(v.key());
			}
		}
		require(done.keySet().equals(r.definitions.keySet()), "extraneous descriptor closure");
		return done.get(r.rootDefinition);
	}

	static void require(boolean condition, String message) {
		if (!condition)
			throw new WorkflowRefusal("PROGRESS_INVALID", message);
	}

}
