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
		return r.scopes.values()
			.stream()
			.filter(s -> s.open() || s.lifecycle.equals("LOCAL_TERMINAL"))
			.max(Comparator.comparingInt(s -> s.depth))
			.orElseThrow(() -> new WorkflowRefusal("STRANDED_PROGRESS", "active run has no work/return: " + r.id));
	}

	static RunState.Node frontier(RunState.Scope s) {
		return s.nodes.values()
			.stream()
			.filter(n -> Set.of("READY", "ENTERED", "WAITING_CHILD").contains(n.phase))
			.findFirst()
			.orElseThrow(() -> new WorkflowRefusal("STRANDED_PROGRESS", "scope has no frontier: " + s.id));
	}

	static void validate(RunState r, WorkflowExecutionBindings resolved) {
		RunIntegrity.validate(r);
		for (var scope : r.scopes.values()) {
			var workflow = resolved.definition(scope.definition);
			require(scope.input.equals(ScopeIds.value(r.id, scope, ScopedValues.root(workflow).identity())),
					"scope root input identity");
			long localDeadline;
			try {
				localDeadline = Math.addExact(scope.opened, workflow.definition().deadline().toMillis());
			}
			catch (ArithmeticException ex) {
				throw new WorkflowRefusal("PROGRESS_INVALID", "scope deadline overflow", ex);
			}
			String origin = workflow.deadlineOrigin();
			if (!scope.parent.isEmpty()) {
				localDeadline = Math.min(localDeadline, r.scopes.get(scope.parent).deadline);
				origin = "INHERITED:" + origin;
				var call = invocation(r, scope.opening);
				var parent = r.scopes.get(scope.parent);
				require(scope.returnNode
					.equals(resolved.definition(parent.definition).graph().unconditionalSuccessor(call.placement)),
						"return continuation");
			}
			require(scope.deadline == localDeadline && scope.deadlineOrigin.equals(origin),
					"absolute scope deadline/origin");
			validatePrefix(r, scope, workflow);
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

	private static void validatePrefix(RunState r, RunState.Scope s, ValidatedWorkflow w) {
		String node = w.graph().startNode();
		Set<String> seen = new HashSet<>();
		while (true) {
			var progress = s.nodes.get(node);
			require(progress != null, "missing graph prefix node: " + node);
			require(seen.add(node), "cyclic graph prefix");
			var graphNode = w.graph().nodeByName(node);
			if (graphNode instanceof WorkflowNode.TerminalNode terminal) {
				require(progress.phase.equals("SETTLED") && s.localOutcome != null, "missing folded terminal outcome");
				var o = s.localOutcome;
				require(o.source().equals(node) && o.status().equals(terminal.intent().name())
						&& o.code().equals("AUTHORED_" + terminal.intent().name())
						&& o.message().equals(terminal.reason()) && o.actor().equals("workflow"),
						"authored terminal outcome");
				if (terminal.successValue() != null)
					require(o.successValue().equals(ScopeIds.value(r.id, s, terminal.successValue())),
							"terminal value identity");
				break;
			}
			require(graphNode instanceof WorkflowNode.StepNode || graphNode instanceof WorkflowNode.CompositeNode,
					"unsupported progress node");
			var call = find(r, s.id, node);
			if (call != null) {
				var binding = w.graph().binding(node);
				require(call.input.equals(ScopeIds.value(r.id, s, binding.input().identity()))
						&& call.output.equals(ScopeIds.value(r.id, s, binding.output().identity())),
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
			node = w.graph().unconditionalSuccessor(node);
		}
		require(seen.equals(s.nodes.keySet()), "saved progress is not a graph prefix");
	}

}
