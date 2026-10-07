package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.markpollack.judge.verdict.Verdict;

import io.github.markpollack.workflow.flows.workflow.EdgeCondition;
import io.github.markpollack.workflow.flows.workflow.WorkflowEdge;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Lowers authoritative region summaries, then checks the resulting graph and fact
 * coordinates.
 */
final class GraphLowering {

	private GraphLowering() {
	}

	static Compilation<?, ?> lower(RegionAnalyzer.Analysis analysis) {
		return lowerDefinition(analysis.definition(), analysis);
	}

	private static <I, O> Compilation<I, O> lowerDefinition(Definition<I, O> definition,
			RegionAnalyzer.Analysis analysis) {
		Map<Placement, Metadata> metadata = new LinkedHashMap<>(analysis.metadata());
		Graph graph = new Graph(metadata, analysis.summaries());
		Placement root = Coordinates.root(definition);
		Fragment body = graph.sequence(definition.nodes(), root);
		Placement completion = root.child("completion", "terminal", 0);
		String finish = completion.graphName();
		graph.addTypedNode(finish, definition.output(), definition.output());
		for (String terminal : graph.terminals)
			graph.edges.add(WorkflowEdge.sequence(terminal, finish));
		metadata.put(completion, new Metadata("completion", definition.output(), definition.output(),
				Map.of("deadline", definition.deadline().toString(), "execution", "requires-validated-runtime")));
		WorkflowGraph<I, O> nativeGraph = new WorkflowGraph<>(definition.name(), graph.nodes, graph.edges, body.entry(),
				finish, analysis.bindings());
		GraphVerification.verify(nativeGraph, metadata, analysis.summaries(), analysis.bindings(), analysis.captures(),
				analysis.products(), analysis.phaseMetadata(), graph.loopBackEdges, definition);
		for (var entry : graph.fragments.entrySet()) {
			Fragment fragment = entry.getValue();
			if (fragment.entry() != null)
				for (String exit : fragment.exits())
					if (!GraphVerification.reachable(nativeGraph, fragment.entry()).contains(exit))
						throw new IllegalArgumentException("unreachable declared region exit: " + entry.getKey());
		}
		return new Compilation<>(definition, nativeGraph, metadata, analysis.bindings(), analysis.captures(),
				analysis.products(), analysis.loops(), analysis.summaries(), analysis.phaseMetadata());
	}

	private record Fragment(String entry, List<String> exits) {
	}

	private static final class Graph {

		final List<WorkflowNode> nodes = new ArrayList<>();

		final List<WorkflowEdge> edges = new ArrayList<>();

		final List<String> terminals = new ArrayList<>();

		final Map<Placement, Metadata> metadata;

		final Map<SummaryKey, RegionSummary> summaries;

		final Map<Placement, Fragment> fragments = new LinkedHashMap<>();

		final Set<WorkflowEdge> loopBackEdges = new HashSet<>();

		Graph(Map<Placement, Metadata> metadata, Map<SummaryKey, RegionSummary> summaries) {
			this.metadata = metadata;
			this.summaries = summaries;
		}

		RegionSummary summary(Placement placement) {
			List<RegionSummary> matches = summaries.values()
				.stream()
				.filter(s -> s.placement().equals(placement))
				.toList();
			if (matches.isEmpty())
				throw new IllegalArgumentException("missing region summary: " + placement);
			RegionSummary first = matches.getFirst();
			for (RegionSummary next : matches) {
				if (first.continues() != next.continues() || !first.normalExits().equals(next.normalExits()))
					throw new IllegalArgumentException("analysis templates disagree on region exit: " + placement);
			}
			return first;
		}

		Fragment fragment(String entry, Placement placement) {
			Fragment fragment = new Fragment(entry,
					summary(placement).normalExits().stream().map(Placement::graphName).toList());
			fragments.put(placement, fragment);
			return fragment;
		}

		void addTypedNode(String id, Type input, Type output) {
			String kind = metadata.entrySet()
				.stream()
				.filter(e -> e.getKey().graphName().equals(id))
				.map(e -> e.getValue().kind())
				.findFirst()
				.orElse("completion");
			if (kind.equals("operation"))
				nodes.add(new WorkflowNode.StepNode(id, input, output));
			else
				nodes.add(new WorkflowNode.ControlNode(id, kind, input, output));
		}

		Fragment sequence(List<Node> ast, Placement parent) {
			String entry = null;
			List<String> previous = new ArrayList<>();
			for (int index = 0; index < ast.size(); index++) {
				Node node = ast.get(index);
				Placement p = Coordinates.node(parent, node, index);
				Fragment part = emit(node, p);
				if (entry == null)
					entry = part.entry();
				for (String from : previous)
					edges.add(WorkflowEdge.sequence(from, part.entry()));
				previous = part.exits();
			}
			List<String> declared = summary(parent).normalExits().stream().map(Placement::graphName).toList();
			if (!declared.equals(previous))
				throw new IllegalArgumentException("sequence exit disagrees with summary");
			return fragment(entry, parent);
		}

		Fragment emit(Node node, Placement p) {
			String id = p.graphName();
			Metadata info = metadata.get(p);
			switch (node) {
				case Choice c -> {
					String join = p.child("join", "choice", 0).graphName();
					String routing = id;
					String declaredJoin = summary(p).continues() ? join : null;
                    nodes.add(new WorkflowNode.DecisionNode(id,info.input(),info.output(),declaredJoin));
					for (int a = 0; a < c.arms().size(); a++) {
						Arm arm = c.arms().get(a);
						Fragment body = sequence(arm.nodes(), p.child("arm", arm.outcome().name(), a));
						String target = body.entry() == null ? join : body.entry();
						edges.add(WorkflowEdge.conditional(routing, target,
								new EdgeCondition.OptionMatch(arm.outcome().name()), arm.outcome().name()));
						for (String exit : body.exits())
							edges.add(WorkflowEdge.sequence(exit, join));
					}
					if (summary(p).continues()) {
						Type output = metadata.get(p.child("join", "choice", 0)).output();
						addTypedNode(join, output, output);
					}
					return fragment(id, p);
				}
				case Parallel group -> {
					String join = p.child("join", "parallel", 0).graphName();
					nodes.add(new WorkflowNode.ForkNode(id, join));
					for (int b = 0; b < group.members().size(); b++) {
						Member member = group.members().get(b);
						Fragment body = sequence(member.nodes(), p.child("member", member.name(), b));
						edges.add(WorkflowEdge.conditional(id, body.entry(), new EdgeCondition.BranchIndex(b),
								member.name()));
						for (String exit : body.exits())
							edges.add(WorkflowEdge.sequence(exit, join));
					}
					// The join retains its analyzed result contract.
					addTypedNode(join, info.output(), info.output());
					return fragment(id, p);
				}
				case Fan fan -> {
					String join = p.child("join", "fan", 0).graphName();
					nodes.add(new WorkflowNode.ForkNode(id, join));
					Fragment body = sequence(fan.body(), p.child("body", "item", 0));
					edges.add(WorkflowEdge.conditional(id, body.entry(), new EdgeCondition.BranchIndex(0),
							"manifest-item"));
					for (String exit : body.exits())
						edges.add(WorkflowEdge.sequence(exit, join));
					addTypedNode(join, info.output(), info.output());
					return fragment(id, p);
				}
				case Loop loop -> {
					addTypedNode(id, info.input(), info.output());
					Fragment body = sequence(loop.body(), p.child("body", "loop", 0));
					String test = p.child("test", loop.test().name(), 0).graphName();
					String exit = p.child("exit", "loop", 0).graphName();
					nodes.add(new WorkflowNode.LoopCheckNode(test,
							metadata.get(p.child("test", loop.test().name(), 0)).input()));
					nodes.add(new WorkflowNode.LoopExitNode(exit));
					edges.add(WorkflowEdge.sequence(id, body.entry()));
					for (String tail : body.exits())
						edges.add(WorkflowEdge.sequence(tail, test));
					WorkflowEdge back = WorkflowEdge.conditional(test, body.entry(), new EdgeCondition.LoopContinue(),
							"continue below cap");
					edges.add(back);
					loopBackEdges.add(back);
					edges.add(WorkflowEdge.conditional(test, exit, new EdgeCondition.LoopExit(),
							"true or configured cap exit"));
					return fragment(id, p);
				}
				case End end -> {
					nodes.add(new WorkflowNode.TerminalNode(id, end.terminal(),
							end.reason() == null ? "" : end.reason(), null));
					terminals.add(id);
					return fragment(id, p);
				}
				case Child child -> {
                    nodes.add(new WorkflowNode.CompositeNode(id, info.input(), info.output(),
                        child.reference() == null ? child.definition().name() : child.reference().authoredIdentity()));
					if (!summary(p).continues()) {
						terminals.add(id);
						return fragment(id, p);
					}
					return fragment(id, p);
				}
				default -> {
					addTypedNode(id, info.input(), info.output());
					return fragment(id, p);
				}
			}
		}

	}

}
