package io.github.markpollack.workflow.flows.workflow;

import java.util.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Binding;

/**
 * Immutable semantic graph shared by validation, inspection and execution. Collection
 * order is storage only: startNode and edges determine traversal. Construction alone does
 * not grant admission; the compiler checks correspondence with the owned definition.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
public final class WorkflowGraph<I, O> {

	private final String name, startNode, finishNode;

	private final List<WorkflowNode> nodes;

	private final List<WorkflowEdge> edges;

	private final List<Binding> bindings;

	private final Map<String, WorkflowNode> nodeIndex;

	private final Map<String, List<WorkflowEdge>> outgoing;

	private final Map<String, List<Binding>> bindingIndex;

	public WorkflowGraph(String name, List<WorkflowNode> nodes, List<WorkflowEdge> edges, String startNode,
			String finishNode, List<Binding> bindings) {
		this.name = Objects.requireNonNull(name);
		this.nodes = List.copyOf(nodes);
		this.edges = List.copyOf(edges);
		this.startNode = Objects.requireNonNull(startNode);
		this.finishNode = Objects.requireNonNull(finishNode);
		this.bindings = List.copyOf(bindings);
		Map<String, WorkflowNode> index = new LinkedHashMap<>();
		for (WorkflowNode node : nodes)
			if (index.putIfAbsent(node.name(), node) != null)
				throw new IllegalArgumentException("duplicate graph ID: " + node.name());
		nodeIndex = Map.copyOf(index);
		Map<String, List<WorkflowEdge>> links = new HashMap<>();
		for (WorkflowEdge edge : edges)
			links.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
		links.replaceAll((id, value) -> List.copyOf(value));
		outgoing = Map.copyOf(links);
		Map<String, List<Binding>> facts = new HashMap<>();
		for (Binding binding : bindings)
			facts.computeIfAbsent(binding.placement().graphName(), ignored -> new ArrayList<>()).add(binding);
		facts.replaceAll((id, value) -> List.copyOf(value));
		bindingIndex = Map.copyOf(facts);
	}

	public static <I, O> WorkflowGraph<I, O> of(String name, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
			String start, String finish) {
		return new WorkflowGraph<>(name, nodes, edges, start, finish, List.of());
	}

	public String name() {
		return name;
	}

	public List<WorkflowNode> nodes() {
		return nodes;
	}

	public List<WorkflowEdge> edges() {
		return edges;
	}

	public String startNode() {
		return startNode;
	}

	public String finishNode() {
		return finishNode;
	}

	public List<Binding> bindings() {
		return bindings;
	}

	public Optional<WorkflowNode> findNode(String id) {
		return Optional.ofNullable(nodeIndex.get(id));
	}

	public WorkflowNode nodeByName(String id) {
		return findNode(id).orElseThrow(() -> new IllegalArgumentException("unknown graph node: " + id));
	}

	public List<WorkflowEdge> edgesFrom(String id) {
		return outgoing.getOrDefault(id, List.of());
	}

	public List<WorkflowEdge> outgoingEdges(String id) {
		return edgesFrom(id);
	}

	public boolean hasDirectEdge(String from, String to) {
		return edgesFrom(from).stream().anyMatch(e -> e.to().equals(to));
	}

	/**
	 * Returns the single checked sequential successor; never chooses the first of
	 * ambiguous edges.
	 */
	public String unconditionalSuccessor(String id) {
		nodeByName(id);
		List<WorkflowEdge> links = edgesFrom(id);
		if (links.size() != 1 || !links.getFirst().isUnconditional())
			throw new IllegalArgumentException("one unconditional successor required at " + id);
		String target = links.getFirst().to();
		nodeByName(target);
		return target;
	}

	/**
	 * Unique ordinary-node binding; phase-specific composition is not sequential
	 * execution.
	 */
	public Binding binding(String id) {
		List<Binding> selected = bindingIndex.getOrDefault(id, List.of());
		if (selected.size() != 1)
			throw new IllegalArgumentException("one node binding required at " + id);
		return selected.getFirst();
	}

}
