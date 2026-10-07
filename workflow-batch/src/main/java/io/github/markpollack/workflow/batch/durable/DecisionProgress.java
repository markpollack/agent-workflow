package io.github.markpollack.workflow.batch.durable;

import java.util.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.workflow.*;
import static io.github.markpollack.workflow.batch.durable.RunIntegrity.require;

/** Saved route and exact capture acceptance using compiler-owned alternatives. */
final class DecisionProgress {

	private DecisionProgress() {
	}

	static String target(ValidatedWorkflow workflow, String node, String outcome) {
		var edges = workflow.graph()
			.edgesFrom(node)
			.stream()
			.filter(e -> e.condition() instanceof EdgeCondition.OptionMatch option
					&& option.optionName().equals(outcome))
			.toList();
		require(edges.size() == 1, "accepted outcome has no unique route");
		return edges.getFirst().to();
	}

	static RunState.Decision accepted(RunState run, RunState.Scope scope, ValidatedWorkflow workflow, String node) {
		return accepted(run, scope, workflow, node, true);
	}

	private static RunState.Decision accepted(RunState run, RunState.Scope scope, ValidatedWorkflow workflow,
			String node, boolean verifyInput) {
		var call = WorkflowProgress.find(run, scope.id, node);
		require(call != null && call.status.equals("SETTLED") && call.outcome.equals("COMMITTED"),
				"route without accepted call");
		var route = run.decisions.get(call.id);
		require(route != null && route.invocation().equals(call.id)
				&& route.target().equals(target(workflow, node, route.outcome())), "route receipt differs");
		String first = route.target();
		while (workflow.graph().nodeByName(first) instanceof WorkflowNode.ControlNode control
				&& control.kind().equals("exclusive-join"))
			first = workflow.graph().unconditionalSuccessor(first);
		if (!route.armInvocation().isEmpty()) {
			var arm = WorkflowProgress.invocation(run, route.armInvocation());
			require(arm.scope.equals(scope.id) && arm.placement.equals(first) && arm.input.equals(route.armInput()),
					"accepted arm input/identity differs");
			ScopedValues.verify(run, scope, workflow.graph().binding(arm.placement).input().identity(), workflow);
		}
		else {
			require(route.armInput().isEmpty(), "arm input without invocation");
			if (verifyInput)
				require(workflow.graph().nodeByName(first) instanceof WorkflowNode.TerminalNode
						|| (scope.localOutcome != null && scope.localOutcome.code().equals("INPUT_ENCODING_FAILED")),
						"accepted decision without exact arm input");
		}
		var graphNode = (WorkflowNode.DecisionNode) workflow.graph().nodeByName(node);
		if (graphNode.joinNodeName() == null || !scope.nodes.containsKey(graphNode.joinNodeName()))
			require(route.captures().isEmpty(), "capture accepted before arm completion");
		return route;
	}

	static String choiceForJoin(ValidatedWorkflow workflow, String join) {
		return workflow.graph()
			.nodes()
			.stream()
			.filter(n -> n instanceof WorkflowNode.DecisionNode d && join.equals(d.joinNodeName()))
			.map(WorkflowNode::name)
			.findFirst()
			.orElseThrow(() -> new WorkflowRefusal("PROGRESS_INVALID", "join without choice"));
	}

	static void join(RunState run, RunState.Scope scope, ValidatedWorkflow workflow, String join) {
		String choice = choiceForJoin(workflow, join);
		var route = accepted(run, scope, workflow, choice, false);
		var sources = new LinkedHashMap<>(route.captures());
		for (var capture : workflow.captures())
			if (capture.placement().graphName().equals(choice)) {
				var selected = capture.routes().get(route.outcome());
				require(selected != null, "capture without selected arm source");
				var original = ScopedValues.verify(run, scope, selected.identity(), workflow);
				var recipe = workflow.values().get(capture.result().identity());
				String id = ScopeIds.value(run.id, scope, recipe.identity());
				require(sources.putIfAbsent(id, original.id) == null, "duplicate capture acceptance");
				ScopedValues.put(run, scope, recipe, original.payload, route.invocation(), original.id);
			}
		run.decisions.put(route.invocation(), new RunState.Decision(route.invocation(), route.outcome(), route.target(),
				route.acceptedAt(), route.armInvocation(), route.armInput(), sources));
	}

	static void verifyJoin(RunState run, RunState.Scope scope, ValidatedWorkflow workflow, String join) {
		String choice = choiceForJoin(workflow, join);
		var route = accepted(run, scope, workflow, choice);
		Map<String, String> expected = new LinkedHashMap<>();
		for (var capture : workflow.captures())
			if (capture.placement().graphName().equals(choice)) {
				var selected = capture.routes().get(route.outcome());
				require(selected != null, "capture source not in selected route");
				String id = ScopeIds.value(run.id, scope, capture.result().identity());
				expected.put(id, ScopeIds.value(run.id, scope, selected.identity()));
				ScopedValues.verify(run, scope, capture.result().identity(), workflow);
			}
		require(expected.equals(route.captures()), "capture acceptance differs from selected arm");
	}

}
