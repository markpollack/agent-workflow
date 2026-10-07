package io.github.markpollack.workflow.batch.durable;

import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;
import static io.github.markpollack.workflow.batch.durable.RunIntegrity.require;

/**
 * Checks saved scope frontiers against the receiving immutable graphs and binding
 * recipes.
 */
final class WorkflowProgress {

	private WorkflowProgress() {
	}

	static RunState.Invocation find(RunState r, String scope, String node) {
		return r.invocations.stream()
			.filter(i -> i.scope.equals(scope) && i.placement.equals(node))
			.findFirst()
			.orElse(null);
	}

	static RunState.Invocation invocation(RunState r, String id) {
		return r.invocations.stream()
			.filter(i -> i.id.equals(id))
			.findFirst()
			.orElseThrow(() -> new WorkflowRefusal("PROGRESS_INVALID", "missing invocation: " + id));
	}

	/** The deepest open frontier or unreturned outcome is the next single transition. */
	static RunState.Scope eligible(RunState r) {
		return eligible(r, Set.of());
	}

	static RunState.Scope eligible(RunState r, Set<String> executingScopes) {
		for (var scope : r.scopes.values()) {
			if (executingScopes.contains(scope.id))
				continue;
			if (scope.lifecycle.equals("LOCAL_TERMINAL") && scope.group.isEmpty() && !scope.parent.isEmpty())
				return scope;
			if (!scope.open())
				continue;
			var node = frontier(scope);
			if (node.phase.equals("WAITING_CHILD"))
				continue;
			if (node.phase.equals("WAITING_GROUP")
					&& !ParallelProgress.ready(r, r.groups.get(ParallelProgress.identity(scope, node.node))))
				continue;
			return scope;
		}
		return null;
	}

	static RunState.Node frontier(RunState.Scope s) {
		return s.nodes.values()
			.stream()
			.filter(n -> Set.of("READY", "ENTERED", "WAITING_CHILD", "WAITING_GROUP").contains(n.phase))
			.findFirst()
			.orElseThrow(() -> new WorkflowRefusal("STRANDED_PROGRESS", "scope has no frontier: " + s.id));
	}

	static void validate(RunState r, WorkflowExecutionBindings resolved) {
		RunIntegrity.validate(r);
		ParallelProgress.validate(r, resolved);
		for (var receipt : r.decisions.values()) {
			var call = invocation(r, receipt.invocation());
			var scope = r.scopes.get(call.scope);
			require(resolved.definition(scope.definition)
				.graph()
				.nodeByName(call.placement) instanceof WorkflowNode.DecisionNode, "receipt attached to non-decision");
		}
		for (var scope : r.scopes.values()) {
			var workflow = resolved.definition(scope.definition);
			require(scope.input.equals(ScopedValues.id(r, scope, ScopedValues.root(workflow).identity())),
					"scope root input identity");
			long localDeadline;
			try {
				localDeadline = Math.addExact(scope.opened, workflow.definition().deadline().toMillis());
			}
			catch (ArithmeticException ex) {
				throw new WorkflowRefusal("PROGRESS_INVALID", "scope deadline overflow", ex);
			}
			String origin = workflow.deadlineOrigin();
			if (!scope.parent.isEmpty() && scope.group.isEmpty()) {
				localDeadline = Math.min(localDeadline, r.scopes.get(scope.parent).deadline);
				origin = "INHERITED:" + origin;
				var call = invocation(r, scope.opening);
				var parent = r.scopes.get(scope.parent);
				require(scope.returnNode
					.equals(resolved.definition(parent.definition).graph().unconditionalSuccessor(call.placement)),
						"return continuation");
			}
			if (!scope.group.isEmpty()) {
				localDeadline = r.scopes.get(scope.parent).deadline;
				origin = r.scopes.get(scope.parent).deadlineOrigin;
			}
			require(scope.deadline == localDeadline && scope.deadlineOrigin.equals(origin),
					"absolute scope deadline/origin");
			validatePrefix(r, scope, workflow, resolved);
			Map<String, ValidatedWorkflow.ValueRecipe> recipes = new HashMap<>();
			workflow.values().values().forEach(v -> recipes.put(ScopeIds.localValue(v.identity()), v));
			for (var value : r.values.values())
				if (scope.id.equals(value.scope)) {
					var recipe = recipes.get(value.local);
					require(recipe != null, "foreign scoped value");
					ScopedValues.verify(r, scope, recipe.identity(), workflow);
				}
		}
	}

	private static void validatePrefix(RunState r, RunState.Scope s, ValidatedWorkflow w,
			WorkflowExecutionBindings resolved) {
		String node = s.group.isEmpty() ? w.graph().startNode() : s.entry;
		Set<String> seen = new HashSet<>();
		while (true) {
			if (!s.group.isEmpty() && node.equals(s.stop)) {
				require(s.localOutcome != null && s.localOutcome.status().equals("SUCCEEDED"),
						"member join lacks local result");
				break;
			}
			var progress = s.nodes.get(node);
			require(progress != null, "missing graph prefix node: " + node);
			require(seen.add(node), "cyclic graph prefix");
			var graphNode = w.graph().nodeByName(node);
			if (graphNode instanceof WorkflowNode.ForkNode fork) {
				var group = r.groups.get(ParallelProgress.identity(s, node));
				if (!progress.phase.equals("SETTLED")) {
					require(group != null || progress.phase.equals("READY") || progress.phase.equals("REVOKED"),
							"fork progress without group");
					break;
				}
				require(group != null && group.phase.equals("SETTLED"), "settled fork without group");
				if (!s.nodes.containsKey(fork.joinNodeName()))
					break;
				node = fork.joinNodeName();
				continue;
			}
			if (graphNode instanceof WorkflowNode.ControlNode control && control.kind().equals("typed-join")) {
				require(progress.phase.equals("SETTLED"), "unsettled parallel join");
				node = w.graph().unconditionalSuccessor(node);
				continue;
			}
			if (graphNode instanceof WorkflowNode.TerminalNode terminal) {
				require(progress.phase.equals("SETTLED") && s.localOutcome != null, "missing folded terminal outcome");
				var o = s.localOutcome;
				require(o.source().equals(node) && o.status().equals(terminal.intent().name())
						&& o.code().equals("AUTHORED_" + terminal.intent().name())
						&& o.message().equals(terminal.reason()) && o.actor().equals("workflow"),
						"authored terminal outcome");
				if (terminal.successValue() != null)
					require(o.successValue().equals(ScopedValues.id(r, s, terminal.successValue())),
							"terminal value identity");
				break;
			}
			if (graphNode instanceof WorkflowNode.ControlNode control && control.kind().equals("exclusive-join")) {
				require(progress.phase.equals("SETTLED") && progress.invocation.isEmpty()
						&& progress.settlement.equals(node), "join acceptance");
				DecisionProgress.verifyJoin(r, s, w, node);
				node = w.graph().unconditionalSuccessor(node);
				continue;
			}
			require(graphNode instanceof WorkflowNode.StepNode || graphNode instanceof WorkflowNode.CompositeNode
					|| graphNode instanceof WorkflowNode.DecisionNode, "unsupported progress node");
			var call = find(r, s.id, node);
			if (call != null) {
				var binding = w.graph().binding(node);
				require(call.input.equals(ScopedValues.id(r, s, binding.input().identity()))
						&& call.output.equals(ScopedValues.id(r, s, binding.output().identity())),
						"invocation binding identity");
				ScopedValues.verify(r, s, binding.input().identity(), w);
			}
			if (!progress.phase.equals("SETTLED"))
				break;
			require(call != null && call.status.equals("SETTLED"), "settled node invocation");
			boolean success = call.kind.equals("LEAF") ? call.outcome.equals("COMMITTED")
					: r.scopes.get(call.child).localOutcome.status().equals("SUCCEEDED");
			if (!success) {
				require(s.localOutcome != null && s.localOutcome.status().equals("FAILED"),
						"non-success call did not close scope");
				if (call.kind.equals("COMPOSITE")) {
					var child = r.scopes.get(call.child).localOutcome;
					require(s.localOutcome.source().equals(child.id()) && s.localOutcome.code().equals(child.code())
							&& s.localOutcome.message().equals(child.message())
							&& s.localOutcome.actor().equals("composite"), "propagated composite cause differs");
				}
				else
					require(s.localOutcome.source().equals(call.id) && s.localOutcome.code().equals(call.outcome),
							"leaf failure cause differs");
				break;
			}
			ScopedValues.verify(r, s, w.graph().binding(node).output().identity(), w);
			if (graphNode instanceof WorkflowNode.DecisionNode) {
				var route = DecisionProgress.accepted(r, s, w, node);
				var output = w.graph().binding(node);
				var routeFact = output.output()
					.type()
					.getTypeName()
					.equals("io.github.markpollack.judge.verdict.Verdict")
							? new io.github.markpollack.workflow.flows.compiler.WorkflowModel.ValueId(
									output.placement(), "conclusion", output.phase())
							: output.output().identity();
				var value = (Enum<?>) ScopedValues.decode(r, s, routeFact, w, resolved);
				require(value.name().equals(route.outcome()), "route differs from accepted enum result");
				node = route.target();
			}
			else
				node = w.graph().unconditionalSuccessor(node);
		}
		require(seen.equals(s.nodes.keySet()), "saved progress is not a graph prefix");
	}

}
