package io.github.markpollack.workflow.batch.durable;

import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import io.github.markpollack.workflow.flows.workflow.*;
import static io.github.markpollack.workflow.batch.durable.RunIntegrity.require;

/** Durable static membership and settlement; no application execution or worker waits. */
final class ParallelProgress {

	private ParallelProgress() {
	}

	static String identity(RunState.Scope scope, String fork) {
		return Digests.fields("group-v1", scope.id, fork);
	}

	static Product product(ValidatedWorkflow workflow, String fork) {
		return workflow.products()
			.stream()
			.filter(p -> p.placement().graphName().equals(fork))
			.findFirst()
			.orElseThrow(() -> new WorkflowRefusal("PROGRESS_INVALID", "fork has no compiled product"));
	}

	static List<WorkflowEdge> branches(ValidatedWorkflow workflow, String fork) {
		return workflow.graph()
			.edgesFrom(fork)
			.stream()
			.sorted(Comparator.comparingInt(e -> ((EdgeCondition.BranchIndex) e.condition()).index()))
			.toList();
	}

	static void open(RunState run, RunState.Scope parent, RunState.Node progress, ValidatedWorkflow workflow,
			long now) {
		var fork = (WorkflowNode.ForkNode) workflow.graph().nodeByName(progress.node);
		var group = new RunState.Group();
		group.id = identity(parent, fork.name());
		group.scope = parent.id;
		group.fork = fork.name();
		group.join = fork.joinNodeName();
		group.opened = now;
		var branches = branches(workflow, fork.name());
		for (int i = 0; i < branches.size(); i++) {
			var edge = branches.get(i);
			var member = new RunState.Scope();
			member.id = Digests.fields("member-v1", group.id, Integer.toString(i));
			member.definition = parent.definition;
			member.parent = parent.id;
			member.group = group.id;
			member.memberPath = product(workflow, fork.name()).placement().child("member", edge.label(), i).graphName();
			member.entry = edge.to();
			member.stop = group.join;
			member.opened = now;
			member.deadline = parent.deadline;
			member.deadlineOrigin = parent.deadlineOrigin;
			member.depth = parent.depth;
			member.input = parent.input;
			member.ready(member.entry);
			require(run.scopes.putIfAbsent(member.id, member) == null, "duplicate parallel member");
			group.members.add(member.id);
		}
		require(run.groups.putIfAbsent(group.id, group) == null, "duplicate group open");
		progress.phase = "WAITING_GROUP";
		run.event("GROUP_OPENED", now, group.id);
	}

	static boolean ready(RunState run, RunState.Group group) {
		return group.members.stream().map(run.scopes::get).allMatch(s -> !s.open());
	}

	static void completeMember(RunState run, RunState.Scope scope, ValidatedWorkflow workflow, long now) {
		var group = run.groups.get(scope.group);
		int index = group.members.indexOf(scope.id);
		var result = product(workflow, group.fork).members().get(index);
		String value = ScopedValues.result(run, scope, result.identity(), workflow);
		run.complete(scope, "SUCCEEDED", "MEMBER_SUCCEEDED", "", "parallel", scope.stop, value, now);
	}

	/**
	 * Seal membership once, preserving declaration order and committed member results.
	 */
	static boolean settle(RunState run, RunState.Scope parent, RunState.Node progress, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved, long now) {
		var group = run.groups.get(identity(parent, progress.node));
		require(group != null && group.phase.equals("OPEN") && ready(run, group), "group is not ready to settle");
		group.phase = "SETTLED";
		group.settled = now;
		progress.phase = "SETTLED";
		progress.settlement = group.id;
		var failed = group.members.stream()
			.map(run.scopes::get)
			.filter(s -> s.localOutcome == null || !s.localOutcome.status().equals("SUCCEEDED"))
			.findFirst()
			.orElse(null);
		for (String id : group.members)
			run.scopes.get(id).lifecycle = "RETURNED";
		run.event("GROUP_SETTLED", now, group.id);
		if (failed != null) {
			run.complete(parent, "FAILED", "PARALLEL_FAILED", "one or more parallel members failed", "parallel",
					group.id, "", now);
			return false;
		}
		var product = product(workflow, group.fork);
		if (!(product.result().type() instanceof ProductType))
			ScopedValues.materialize(run, parent, product.result().identity(), workflow, resolved, group.id);
		var join = parent.ready(group.join);
		join.phase = "SETTLED";
		join.settlement = group.id;
		return true;
	}

	static void validate(RunState run, WorkflowExecutionBindings resolved) {
		for (var entry : run.groups.entrySet()) {
			var group = entry.getValue();
			var parent = run.scopes.get(group.scope);
			require(parent != null && entry.getKey().equals(group.id) && group.id.equals(identity(parent, group.fork)),
					"group identity");
			var workflow = resolved.definition(parent.definition);
			require(workflow.graph().nodeByName(group.fork) instanceof WorkflowNode.ForkNode fork
					&& fork.joinNodeName().equals(group.join), "group graph correspondence");
			var branches = branches(workflow, group.fork);
			require(group.members.size() == branches.size() && new HashSet<>(group.members).size() == branches.size(),
					"group membership");
			require(group.opened >= parent.opened && group.opened < parent.deadline, "group opening time");
			for (int i = 0; i < branches.size(); i++) {
				var member = run.scopes.get(group.members.get(i));
				var edge = branches.get(i);
				require(member != null && member.id.equals(Digests.fields("member-v1", group.id, Integer.toString(i)))
						&& member.group.equals(group.id) && member.parent.equals(parent.id)
						&& member.definition.equals(parent.definition) && member.entry.equals(edge.to())
						&& member.stop.equals(group.join)
						&& member.memberPath.equals(
								product(workflow, group.fork).placement().child("member", edge.label(), i).graphName())
						&& member.opened == group.opened && member.deadline == parent.deadline
						&& member.deadlineOrigin.equals(parent.deadlineOrigin) && member.depth == parent.depth
						&& member.input.equals(parent.input) && member.opening.isEmpty() && member.returnNode.isEmpty(),
						"member coordinate/snapshot/bounds");
				if (member.localOutcome != null && member.localOutcome.status().equals("SUCCEEDED")) {
					var fact = product(workflow, group.fork).members().get(i);
					require(member.localOutcome.successValue().equals(ScopedValues.id(run, member, fact.identity()))
							&& member.localOutcome.source().equals(group.join)
							&& member.localOutcome.code().equals("MEMBER_SUCCEEDED")
							&& member.localOutcome.actor().equals("parallel"), "member result provenance");
					ScopedValues.result(run, member, fact.identity(), workflow);
				}
			}
			var node = parent.nodes.get(group.fork);
			require(node != null && node.invocation.isEmpty(), "group node");
			switch (group.phase) {
				case "OPEN" -> require(parent.open() && node.phase.equals("WAITING_GROUP") && group.settled == 0,
						"open group frontier");
				case "SETTLED" -> {
					require(ready(run, group) && group.settled >= group.opened && group.settled < parent.deadline
							&& node.phase.equals("SETTLED") && node.settlement.equals(group.id), "group settlement");
					boolean success = group.members.stream()
						.map(run.scopes::get)
						.allMatch(s -> s.localOutcome != null && s.localOutcome.status().equals("SUCCEEDED"));
					require(success == parent.nodes.containsKey(group.join), "join without complete required results");
					for (String id : group.members)
						require(run.scopes.get(id).lifecycle.equals("RETURNED"), "member settlement lifecycle");
					if (!success)
						require(parent.localOutcome != null && parent.localOutcome.code().equals("PARALLEL_FAILED")
								&& parent.localOutcome.source().equals(group.id)
								&& parent.localOutcome.acceptedAt() == group.settled, "group failure propagation");
					else {
						var join = parent.nodes.get(group.join);
						require(join.phase.equals("SETTLED") && join.settlement.equals(group.id)
								&& join.invocation.isEmpty(), "join settlement");
						var fact = product(workflow, group.fork).result();
						if (!(fact.type() instanceof ProductType))
							ScopedValues.verify(run, parent, fact.identity(), workflow);
					}
				}
				case "REVOKED" ->
					require(!parent.open() && ready(run, group) && group.settled >= group.opened, "revoked group");
				default -> throw new WorkflowRefusal("PROGRESS_INVALID", "unknown group phase");
			}
		}
		long members = run.groups.values().stream().mapToLong(g -> g.members.size()).sum();
		require(members == run.scopes.values().stream().filter(s -> !s.group.isEmpty()).count(),
				"foreign member scope");
	}

}
