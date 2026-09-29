package io.github.markpollack.workflow.flows.compiler;

import java.util.*;
import io.github.markpollack.workflow.flows.workflow.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Checks emitted graph topology and type/fact metadata against analyzed regions. The
 * graph holds semantic metadata, not registered application Step instances. Its full Type
 * fields must agree with the analyzed metadata, including generic arguments; this
 * consistency check is separate from runtime declaration validation in StepTypes.
 * Bindings and captures retain source identities and full Types throughout verification.
 */
final class GraphVerification {

	private GraphVerification() {
	}

	static Set<String> reachable(WorkflowGraph<?, ?> graph, String start) {
		Map<String, List<String>> successors = new HashMap<>();
		graph.edges().forEach(e -> successors.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()));
		Set<String> seen = new HashSet<>();
		Deque<String> todo = new ArrayDeque<>();
		todo.add(start);
		while (!todo.isEmpty()) {
			String node = todo.remove();
			if (seen.add(node))
				todo.addAll(successors.getOrDefault(node, List.of()));
		}
		return seen;
	}

	static void verify(WorkflowGraph<?, ?> graph, Map<Placement, Metadata> metadata,
			Map<SummaryKey, RegionSummary> summaries, List<Binding> bindings, List<Capture> captures,
			List<Product> products, Map<SummaryKey, Metadata> phaseMetadata, Set<WorkflowEdge> ownedBackEdges,
			Definition<?, ?> definition) {
		require(new HashSet<>(graph.bindings()).equals(new HashSet<>(bindings))
				&& graph.bindings().size() == bindings.size(), "graph binding disagreement");
		Set<WorkflowEdge> provenBackEdges = RegionTopology.verify(definition, graph, summaries);
		require(provenBackEdges.equals(ownedBackEdges), "loop ownership disagrees with regions");
		Set<String> ids = new HashSet<>();
		for (WorkflowNode node : graph.nodes())
			require(ids.add(node.name()), "duplicate graph ID");
		require(ids.contains(graph.startNode()) && ids.contains(graph.finishNode()), "unknown graph boundary");
		Set<String> metaIds = new HashSet<>();
		metadata.keySet().forEach(p -> metaIds.add(p.graphName()));
		require(ids.equals(metaIds), "graph/metadata coordinate disagreement");
		Set<WorkflowEdge> distinct = new HashSet<>();
		for (WorkflowEdge edge : graph.edges()) {
			require(ids.contains(edge.from()) && ids.contains(edge.to()), "dangling edge");
			require(distinct.add(edge), "duplicate graph edge");
			if (edge.condition() instanceof EdgeCondition.LoopContinue)
				require(ownedBackEdges.contains(edge), "unowned loop back edge");
		}
		require(graph.edges().containsAll(ownedBackEdges), "missing owned back edge");
		require(reachable(graph, graph.startNode()).equals(ids), "unreachable emitted work");
		for (String id : ids)
			require(id.equals(graph.finishNode()) || graph.edges().stream().anyMatch(e -> e.from().equals(id)),
					"graph dead end");
		require(graph.edges().stream().noneMatch(e -> e.from().equals(graph.finishNode())), "completion has successor");
		for (var item : metadata.entrySet()) {
			WorkflowNode node = graph.findNode(item.getKey().graphName()).orElseThrow();
			if (node instanceof WorkflowNode.StepNode step) {
				verifyTypes(step.input(), step.output(), item.getValue());
			}
			else if (node instanceof WorkflowNode.CompositeNode composite) {
                verifyTypes(composite.input(), composite.output(), item.getValue());
            }
            else if (node instanceof WorkflowNode.ControlNode control) {
				verifyTypes(control.input(), control.output(), item.getValue());
			}
			else if (node instanceof WorkflowNode.DecisionNode decision) {
				verifyTypes(decision.input(), decision.output(), item.getValue());
			}

			if (item.getValue().kind().equals("terminal")) {
				Terminal intent = Terminal.valueOf(item.getValue().configuration().get("intent"));
				require(node instanceof WorkflowNode.TerminalNode terminal && terminal.intent() == intent
						&& Objects.equals(terminal.reason(), item.getValue().configuration().get("reason")),
						"terminal node disagreement");
				require(summaries.values()
					.stream()
					.anyMatch(s -> s.placement().equals(item.getKey()) && !s.continues()
							&& s.terminals().contains(intent)),
						"terminal summary disagreement");
				require(graph.edges()
					.stream()
					.filter(e -> e.from().equals(item.getKey().graphName()))
					.allMatch(e -> e.to().equals(graph.finishNode())), "terminal exports normal continuation");
			}
		}
		// Removing the explicitly owned loop back edges must leave a DAG.
		Map<String, Integer> incoming = new HashMap<>();
		ids.forEach(id -> incoming.put(id, 0));
		Map<String, List<String>> outgoing = new HashMap<>();
		for (WorkflowEdge edge : graph.edges())
			if (!ownedBackEdges.contains(edge)) {
				incoming.merge(edge.to(), 1, Integer::sum);
				outgoing.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
			}
		Deque<String> ready = new ArrayDeque<>();
		incoming.forEach((id, n) -> {
			if (n == 0)
				ready.add(id);
		});
		int visited = 0;
		while (!ready.isEmpty()) {
			String id = ready.remove();
			visited++;
			for (String target : outgoing.getOrDefault(id, List.of()))
				if (incoming.merge(target, -1, Integer::sum) == 0)
					ready.add(target);
		}
		require(visited == ids.size(), "unowned graph cycle");
		Set<Placement> coordinates = new HashSet<>(metadata.keySet());
		summaries.keySet().forEach(k -> coordinates.add(k.placement()));
		Map<ValueId, Fact> seen = new HashMap<>();
		for (Binding binding : bindings) {
			require(ids.contains(binding.placement().graphName()), "binding has no graph coordinate");
			Metadata info = phaseMetadata.get(new SummaryKey(binding.placement(), binding.phase()));
			require(info != null, "binding has no phase metadata");
			require(info.input().equals(binding.input().type()) && info.output().equals(binding.output().type()),
					"binding/type metadata disagreement");
			if (info.kind().equals("operation")) {
				RegionSummary summary = summaries.get(new SummaryKey(binding.placement(), binding.phase()));
				require(summary != null && summary.continues() && binding.output().equals(summary.result()),
						"operation result disagrees with summary");
			}
			fact(binding.input(), coordinates, seen);
			fact(binding.output(), coordinates, seen);
		}
		for (Capture capture : captures) {
			fact(capture.result(), coordinates, seen);
			capture.alternatives().forEach(f -> fact(f, coordinates, seen));
		}
		for (Product product : products) {
			fact(product.result(), coordinates, seen);
			product.members().forEach(f -> fact(f, coordinates, seen));
		}
		for (RegionSummary summary : summaries.values()) {
			require(summary.normalExits().stream().allMatch(p -> ids.contains(p.graphName())),
					"summary exit has no graph coordinate");
			if (summary.result() != null)
				fact(summary.result(), coordinates, seen);
		}
	}

	private static void fact(Fact fact, Set<Placement> coordinates, Map<ValueId, Fact> seen) {
		require(coordinates.contains(fact.identity().placement()), "value has no legal coordinate");
		Fact prior = seen.putIfAbsent(fact.identity(), fact);
		if (prior != null) {
			require(prior.equals(fact), "value identity has conflicting definitions");
			return;
		}
		fact.components().forEach(f -> fact(f, coordinates, seen));
		fact.consumed().forEach(f -> fact(f, coordinates, seen));
	}

	/**
	 * Check the emitter's full type metadata, including generic arguments and internal
	 * products.
	 */
	static void verifyTypes(java.lang.reflect.Type input, java.lang.reflect.Type output, Metadata expected) {
		require(Objects.equals(input, expected.input()) && Objects.equals(output, expected.output()),
				"graph node type disagreement");
	}

	private static void require(boolean condition, String diagnostic) {
		if (!condition)
			throw new IllegalArgumentException(diagnostic);
	}

}
