package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;
import java.util.*;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Immutable registry-independent result of owned definition, structural, binding, type
 * and graph validation. The runtime follows this graph; no ordered invocation program is
 * retained. Supplied Step associations are separate local preparation data, not graph
 * semantics or durable identity. Raw graphs and Compilation are not admission tokens.
 */
public final class ValidatedWorkflow {

	public static final String COMPILER_CONTRACT = "structured-workflow-compiler-v6";

	private static final Set<Capability> SUPPORTED = Set.of(Capability.OPERATION, Capability.TERMINAL, Capability.CHILD,
			Capability.DECISION, Capability.VERDICT, Capability.PARALLEL, Capability.FAN);

	/**
	 * Exact value contract and provenance prepared from an analyzed fact. Components
	 * retain declaration order for a newly assembled record; an alias retains the source
	 * identity instead. Runtime recovery reads and verifies saved bytes at these IDs; it
	 * must not reconstruct a missing historical effective input from newer values. A
	 * structural ProductType has no application codec contract or payload; its immutable
	 * component references retain the typed member results. No business payload or
	 * supplied application object is retained here.
	 */
	public record ValueRecipe(ValueId identity, Type declaration, TypeContracts.Contract contract,
			List<ValueId> components, List<ValueId> consumed) {
		public ValueRecipe {
			components = List.copyOf(components);
			consumed = List.copyOf(consumed);
		}
	}

	private final Definition<?, ?> definition;

	private final WorkflowGraph<?, ?> graph;

	private final RegionSummary rootSummary;

	private final Map<Placement, Step<?, ?>> suppliedSteps;

	private final Map<Placement, ValidatedWorkflow> children;

	private final Map<ValueId, ValueRecipe> values;

	private final List<Capture> captures;

	private final List<Product> products;

	private final TypeContracts.Contract input, output;

	private final String authoredIdentity;

	private final TypeContracts.CodecIdentity codec;

	private final DeadlinePolicy deadlinePolicy;

	private final java.time.Duration authoredDuration;

	private final String deadlineOrigin;

	/**
	 * Prepare a semantic graph and value recipes from checked bindings, checking complete
	 * placement coverage and exact type agreement with selections. The authored
	 * fingerprint covers behavior/value identities and deadline policy; selected
	 * deployment identities are checked separately by runtime compatibility. The private
	 * constructor cannot admit a caller-authored binding map or unverified graph.
	 */
	private ValidatedWorkflow(Compilation<?, ?> compiled, Map<Placement, Step<?, ?>> selections,
			Map<Placement, ValidatedWorkflow> children, DeadlinePolicy policy, java.time.Duration authoredDuration) {
		this.children = Map.copyOf(children);
		this.deadlinePolicy = policy;
		this.authoredDuration = authoredDuration;
		this.deadlineOrigin = policy.origin(authoredDuration);
		definition = compiled.definition();
		captures = compiled.captures();
		products = compiled.products();
		suppliedSteps = Map.copyOf(selections);
		rootSummary = compiled.summaries().get(new SummaryKey(Coordinates.root(definition), "root"));
		TypeContracts contracts = new TypeContracts();
		codec = contracts.identity();
		input = contracts.contract(definition.input());
		output = contracts.contract(definition.output());

		validateSuppliedSteps(compiled, selections, children, contracts);
		Map<ValueId, ValueRecipe> recipes = valueRecipes(compiled, contracts);
		graph = withTerminalValue(compiled);
		values = Collections.unmodifiableMap(recipes);
		authoredIdentity = authoredIdentity(contracts);
	}

	private static void validateSuppliedSteps(Compilation<?, ?> compiled, Map<Placement, Step<?, ?>> selections,
			Map<Placement, ValidatedWorkflow> children, TypeContracts contracts) {
		Set<Placement> required = new HashSet<>();
		for (Binding binding : compiled.bindings()) {
			if (children.containsKey(binding.placement()))
				continue;
			required.add(binding.placement());
			Step<?, ?> selected = selections.get(binding.placement());
			if (selected == null)
				throw new IllegalArgumentException("missing executable selection at " + binding.placement());
			StepTypes declaration = StepTypes.of(selected.getClass());
			if (!contracts.compatible(binding.input().type(), declaration.input())
					|| !contracts.compatible(binding.output().type(), declaration.output()))
				throw new IllegalArgumentException("executable contract disagreement at " + binding.placement());
		}
		if (!required.equals(selections.keySet()))
			throw new IllegalArgumentException("extraneous executable selection");
	}

	private static Map<ValueId, ValueRecipe> valueRecipes(Compilation<?, ?> compiled, TypeContracts contracts) {
		Map<ValueId, ValueRecipe> recipes = new LinkedHashMap<>();
		for (Binding binding : compiled.bindings()) {
			recipe(binding.input(), recipes, contracts);
			recipe(binding.output(), recipes, contracts);
			if (binding.output().type().getTypeName().equals("io.github.markpollack.judge.verdict.Verdict")
					&& compiled.graph()
						.nodeByName(binding.placement().graphName()) instanceof WorkflowNode.DecisionNode)
				recipe(new Fact(new ValueId(binding.placement(), "conclusion", binding.phase()),
						binding.operation() + ".conclusion",
						io.github.markpollack.judge.verdict.Verdict.Conclusion.class, List.of(),
						List.of(binding.output())), recipes, contracts);
		}
		for (Capture capture : compiled.captures()) {
			recipe(capture.result(), recipes, contracts);
			capture.alternatives().forEach(f -> recipe(f, recipes, contracts));
		}
		compiled.summaries()
			.values()
			.stream()
			.filter(s -> s.result() != null)
			.forEach(s -> recipe(s.result(), recipes, contracts));
		return recipes;
	}

	/**
	 * The graph predecessor, not binding storage order, determines the terminal value.
	 */
	private static WorkflowGraph<?, ?> withTerminalValue(Compilation<?, ?> compiled) {
		var source = compiled.graph();
		List<WorkflowNode> nodes = new ArrayList<>();
		for (WorkflowNode node : source.nodes()) {
			if (node instanceof WorkflowNode.TerminalNode terminal && terminal.intent() == Terminal.SUCCEEDED) {
				ValueId result = compiled.summaries()
					.values()
					.stream()
					.filter(s -> s.placement().graphName().equals(terminal.name()) && s.result() != null)
					.map(s -> s.result().identity())
					.findFirst()
					.orElseThrow();
				node = new WorkflowNode.TerminalNode(terminal.name(), terminal.intent(), terminal.reason(), result);
			}
			nodes.add(node);
		}
		return new WorkflowGraph<>(source.name(), nodes, source.edges(), source.startNode(), source.finishNode(),
				source.bindings());
	}

	private String authoredIdentity(TypeContracts contracts) {
		StringBuilder authored = new StringBuilder();
		for (String field : List.of(COMPILER_CONTRACT, Coordinates.SCHEME, definition.name(),
				definition.deadline().toString()))
			IdentityEncoding.field(authored, field);
		for (String field : List.of(deadlinePolicy.profile(), deadlinePolicy.maximum().toString(),
				authoredDuration == null ? "default" : authoredDuration.toString(), deadlineOrigin))
			IdentityEncoding.field(authored, field);
		contractIdentity(authored, input);
		contractIdentity(authored, output);
		IdentityEncoding.field(authored, graph.startNode());
		IdentityEncoding.field(authored, graph.finishNode());
		for (var node : graph.nodes().stream().sorted(Comparator.comparing(n -> n.name())).toList()) {
			IdentityEncoding.field(authored, node.name());
			IdentityEncoding.field(authored, node.getClass().getSimpleName());
			if (node instanceof WorkflowNode.StepNode step) {
				contractIdentity(authored, contracts.contract(step.input()));
				contractIdentity(authored, contracts.contract(step.output()));
				Binding binding = graph.binding(node.name());
				valueIdentity(authored, binding.input().identity());
				valueIdentity(authored, binding.output().identity());
			}
			if (node instanceof WorkflowNode.DecisionNode decision) {
				contractIdentity(authored, contracts.contract(decision.input()));
				contractIdentity(authored, contracts.contract(decision.output()));
			}
			if (node instanceof WorkflowNode.CompositeNode composite) {
				contractIdentity(authored, contracts.contract(composite.input()));
				contractIdentity(authored, contracts.contract(composite.output()));
				IdentityEncoding.field(authored, composite.authoredDefinition());
				Binding binding = graph.binding(node.name());
				valueIdentity(authored, binding.input().identity());
				valueIdentity(authored, binding.output().identity());
			}
			if (node instanceof WorkflowNode.ForkNode fork) {
				IdentityEncoding.field(authored, fork.joinNodeName());
				Fan fan = fans().get(fork.name());
				if (fan != null) {
					IdentityEncoding.field(authored, Integer.toString(fan.maxItems()));
					IdentityEncoding.field(authored, Integer.toString(fan.maxInFlight()));
				}
			}
			if (node instanceof WorkflowNode.ControlNode control)
				IdentityEncoding.field(authored, control.kind());
			if (node instanceof WorkflowNode.TerminalNode terminal) {
				IdentityEncoding.field(authored, terminal.intent().name());
				IdentityEncoding.field(authored, terminal.reason());
				if (terminal.successValue() != null)
					valueIdentity(authored, terminal.successValue());
			}
		}
		graph.edges()
			.stream()
			.sorted(Comparator.comparing(io.github.markpollack.workflow.flows.workflow.WorkflowEdge::from)
				.thenComparing(io.github.markpollack.workflow.flows.workflow.WorkflowEdge::to))
			.forEach(edge -> {
				IdentityEncoding.field(authored, edge.from());
				IdentityEncoding.field(authored, edge.to());
				IdentityEncoding.field(authored, edge.condition().toString());
			});
		children.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().graphName())).forEach(e -> {
			IdentityEncoding.field(authored, e.getKey().graphName());
			IdentityEncoding.field(authored, e.getValue().authoredIdentity());
		});
		for (ValueRecipe value : values.values()
			.stream()
			.sorted(Comparator.comparing(v -> v.identity().toString()))
			.toList()) {
			valueIdentity(authored, value.identity());
			if (value.contract() == null)
				IdentityEncoding.field(authored, value.declaration().getTypeName());
			else
				contractIdentity(authored, value.contract());
			IdentityEncoding.field(authored, Integer.toString(value.components().size()));
			value.components().forEach(id -> valueIdentity(authored, id));
			IdentityEncoding.field(authored, Integer.toString(value.consumed().size()));
			value.consumed().forEach(id -> valueIdentity(authored, id));
		}

		for (Capture capture : captures) {
			valueIdentity(authored, capture.result().identity());
			IdentityEncoding.field(authored, Integer.toString(capture.alternatives().size()));
			capture.alternatives().forEach(f -> valueIdentity(authored, f.identity()));
			new TreeMap<>(capture.routes()).forEach((route, fact) -> {
				IdentityEncoding.field(authored, route);
				valueIdentity(authored, fact.identity());
			});
		}
		return IdentityEncoding.digest(authored.toString());
	}

	/**
	 * Fluent authoring bridge: derive the successful result from shared region analysis.
	 * Every successful terminal must agree; explicit programmatic compile still checks
	 * the supplied output contract. This uses the same ownership, normalization,
	 * analysis, lowering and verification boundary and invokes no application work.
	 * @param source authored topology with a concrete provisional output declaration
	 * @param selections exact supplied operation associations
	 * @return immutable workflow with its compiler-derived successful output contract
	 */
	public static ValidatedWorkflow compileInferred(Definition<?, ?> source, Map<Placement, Step<?, ?>> selections) {
		return new CompilationSession().compileOwned(DefinitionOwnership.acquire(source), selections, Map.of(),
				DeadlinePolicy.DEFAULT, true);
	}

	/**
	 * Compiles and checks one owned definition for the fixed initial capability set.
	 * Selections are supplied objects keyed by stable authored call placements.
	 * Unsupported composition refuses; support can expand only with compiler/runtime
	 * proof. Authored identity fields must contain well-formed Unicode; valid text is not
	 * normalized.
	 */
	public static ValidatedWorkflow compile(Definition<?, ?> source, Map<Placement, Step<?, ?>> selections) {
		return compile(source, selections, DeadlinePolicy.DEFAULT);
	}

	/**
	 * Snapshot and validate a programmatic definition with a finite deadline policy.
	 * Reject unsupported constructs before region analysis. The same owned definition is
	 * analyzed and lowered; checked bindings remain facts on the resulting graph.
	 * @param source caller-owned construction data; do not mutate during this call
	 * @param selections exact operation selections keyed by compiler placements
	 * @param policy finite maximum/default used when resolving the authored duration
	 * @return immutable checked definition, graph, selections and value recipes
	 * @throws IllegalArgumentException for malformed structure/types, ambiguous or
	 * missing bindings, unsupported capabilities or missing/extraneous selections
	 */
	public static ValidatedWorkflow compile(Definition<?, ?> source, Map<Placement, Step<?, ?>> selections,
			DeadlinePolicy policy) {
		return compile(source, selections, Map.of(), policy);
	}

	/**
	 * Compile one owned definition with exact local Step and reusable-definition
	 * associations. Direct immutable Child references need no additional map entry.
	 * Lower-level Child declarations must agree with their selected definition, including
	 * authored duration. All callers use this boundary; no registry or application
	 * execution is involved.
	 * @param source construction data, copied before analysis
	 * @param selections local supplied Steps keyed by placement
	 * @param children selected definitions for unresolved Child declarations
	 * @param policy finite duration policy for this definition
	 * @return immutable graph, bindings and separate supplied associations
	 */
	public static ValidatedWorkflow compile(Definition<?, ?> source, Map<Placement, Step<?, ?>> selections,
			Map<Placement, ValidatedWorkflow> children, DeadlinePolicy policy) {
		return new CompilationSession().compileOwned(DefinitionOwnership.acquire(source), selections, children, policy);
	}

	/**
	 * Associate supplied Steps with local Calls and Choices in lexical authored order,
	 * then use the shared compiler.
	 */
	public static ValidatedWorkflow compile(Definition<?, ?> source, List<? extends Step<?, ?>> steps,
			DeadlinePolicy policy) {
		Definition<?, ?> owned = DefinitionOwnership.acquire(source);
		Map<Placement, Step<?, ?>> selected = new LinkedHashMap<>();
		var remaining = steps.iterator();
		collectSelections(owned.nodes(), Coordinates.root(owned), remaining, selected);
		if (remaining.hasNext())
			throw new IllegalArgumentException("extraneous operation selection");
		return new CompilationSession().compileOwned(owned, selected, Map.of(), policy);
	}

	private static void collectSelections(List<Node> nodes, Placement parent, Iterator<? extends Step<?, ?>> remaining,
			Map<Placement, Step<?, ?>> selected) {
		for (int i = 0; i < nodes.size(); i++) {
			Node node = nodes.get(i);
			Placement at = Coordinates.node(parent, node, i);
			if (node instanceof Call || node instanceof Choice) {
				if (!remaining.hasNext())
					throw new IllegalArgumentException("missing operation selection");
				selected.put(at, remaining.next());
			}
			if (node instanceof Parallel group)
				for (int m = 0; m < group.members().size(); m++) {
					var member = group.members().get(m);
					collectSelections(member.nodes(), at.child("member", member.name(), m), remaining, selected);
				}
			if (node instanceof Fan f)
				collectSelections(f.body(), at.child("body", "item", 0), remaining, selected);
			if (node instanceof Choice choice)
				for (int arm = 0; arm < choice.arms().size(); arm++) {
					var body = choice.arms().get(arm);
					collectSelections(body.nodes(), at.child("arm", body.outcome().name(), arm), remaining, selected);
				}
		}
	}

	private static final class CompilationSession {

		private final IdentityHashMap<Definition<?, ?>, IdentityHashMap<ValidatedWorkflow, Boolean>> checked = new IdentityHashMap<>();

		private final IdentityHashMap<ValidatedWorkflow, Set<ValidatedWorkflow>> compared = new IdentityHashMap<>();

		/**
		 * An explicit reference carries its supplied objects as well as authored shape.
		 */
		private void requireSameSelection(ValidatedWorkflow declared, ValidatedWorkflow selected, Placement at) {
			record Pair(ValidatedWorkflow declared, ValidatedWorkflow selected) {
			}
			Deque<Pair> todo = new ArrayDeque<>();
			todo.push(new Pair(declared, selected));
			while (!todo.isEmpty()) {
				Pair pair = todo.pop();
				var a = pair.declared();
				var b = pair.selected();
				if (a == b || !compared.computeIfAbsent(a, key -> Collections.newSetFromMap(new IdentityHashMap<>()))
					.add(b))
					continue;
				if (!a.authoredIdentity.equals(b.authoredIdentity))
					throw new IllegalArgumentException("child definition disagreement at " + at);
				if (!a.suppliedSteps.keySet().equals(b.suppliedSteps.keySet())
						|| a.suppliedSteps.entrySet()
							.stream()
							.anyMatch(e -> e.getValue() != b.suppliedSteps.get(e.getKey()))
						|| !a.children.keySet().equals(b.children.keySet()))
					throw new IllegalArgumentException("child reference selection disagreement at " + at);
				a.children.forEach((position, child) -> todo.push(new Pair(child, b.children.get(position))));
			}
		}

		// Ownership is acquired once at the public boundary. Re-copying a subtree here
		// would replace the identity keys used to memoize a shared declaration DAG.
		ValidatedWorkflow compileOwned(Definition<?, ?> owned, Map<Placement, Step<?, ?>> steps,
				Map<Placement, ValidatedWorkflow> children, DeadlinePolicy policy) {
			return compileOwned(owned, steps, children, policy, false);
		}

		ValidatedWorkflow compileOwned(Definition<?, ?> owned, Map<Placement, Step<?, ?>> steps,
				Map<Placement, ValidatedWorkflow> children, DeadlinePolicy policy, boolean inferOutput) {
			Objects.requireNonNull(policy, "deadline policy");
			var authored = owned.deadline();
			Map<Placement, ValidatedWorkflow> references = new LinkedHashMap<>();
			Set<Placement> required = new HashSet<>();
			List<Node> nodes = normalize(owned.nodes(), Coordinates.root(owned), children, references, required);
			if (!required.equals(children.keySet()))
				throw new IllegalArgumentException("extraneous validated child selection");
			if (nodes.stream()
				.noneMatch(n -> n instanceof Call || n instanceof Child || n instanceof Choice || n instanceof Parallel
						|| n instanceof Fan))
				throw new IllegalArgumentException("workflow requires at least one Step or composite");
			owned = new Definition<>(owned.name(), owned.input(), owned.output(), List.copyOf(nodes),
					policy.resolve(authored));
			return new ValidatedWorkflow(
					inferOutput ? StructuredWorkflowCompiler.compileInferredOwned(owned)
							: StructuredWorkflowCompiler.compileOwned(owned),
					Map.copyOf(steps), references, policy, authored);
		}

		private List<Node> normalize(List<Node> body, Placement parent, Map<Placement, ValidatedWorkflow> children,
				Map<Placement, ValidatedWorkflow> references, Set<Placement> required) {
			List<Node> nodes = new ArrayList<>();
			for (int i = 0; i < body.size(); i++) {
				Node node = body.get(i);
				if (!SUPPORTED.contains(Coordinates.capability(node)))
					throw new IllegalArgumentException(
							"unsupported production capability: " + Coordinates.capability(node));
				if (node instanceof Parallel group) {
					Placement position = Coordinates.node(parent, node, i);
					List<Member> members = new ArrayList<>();
					for (int m = 0; m < group.members().size(); m++) {
						var member = group.members().get(m);
						members.add(new Member(member.name(), normalize(member.nodes(),
								position.child("member", member.name(), m), children, references, required)));
					}
					nodes.add(new Parallel(group.id(), group.output(), group.allSuccessful(), members));
				}
				else if (node instanceof Fan fan) {
					Placement position = Coordinates.node(parent, node, i);
					nodes.add(new Fan(fan.id(), fan.element(), fan.maxItems(), fan.maxInFlight(), fan.allSuccessful(),
							normalize(fan.body(), position.child("body", "item", 0), children, references, required)));
				}
				else if (node instanceof Choice choice) {
					Placement position = Coordinates.node(parent, node, i);
					List<Arm> arms = new ArrayList<>();
					for (int a = 0; a < choice.arms().size(); a++) {
						Arm arm = choice.arms().get(a);
						arms.add(new Arm(arm.outcome(), normalize(arm.nodes(),
								position.child("arm", arm.outcome().name(), a), children, references, required)));
					}
					nodes.add(new Choice(choice.id(), choice.operation(), choice.assessment(), arms));
				}
				else if (node instanceof Child child) {
					Placement placement = Coordinates.node(parent, node, i);
					ValidatedWorkflow selected = child.reference();
					if (selected == null) {
						required.add(placement);
						selected = children.get(placement);
						if (selected == null)
							throw new IllegalArgumentException("missing validated child selection at " + placement);
						var matches = checked.computeIfAbsent(child.definition(), d -> new IdentityHashMap<>());
						if (!matches.containsKey(selected)) {
							Map<Placement, ValidatedWorkflow> nested = new LinkedHashMap<>();
							unresolved(child.definition().nodes(), Coordinates.root(child.definition()), selected,
									nested);
							ValidatedWorkflow expected = compileOwned(child.definition(), selected.suppliedSteps,
									nested, selected.deadlinePolicy);
							requireSameSelection(expected, selected, placement);
							matches.put(selected, Boolean.TRUE);
						}
					}
					references.put(placement, selected);
					nodes.add(new Child(child.id(), selected));
				}
				else
					nodes.add(node);
			}
			return List.copyOf(nodes);
		}

		private void unresolved(List<Node> body, Placement parent, ValidatedWorkflow selected,
				Map<Placement, ValidatedWorkflow> nested) {
			for (int i = 0; i < body.size(); i++) {
				Node node = body.get(i);
				Placement at = Coordinates.node(parent, node, i);
				if (node instanceof Child c && c.reference() == null) {
					var target = selected.children.get(at);
					if (target == null)
						throw new IllegalArgumentException("missing validated child selection at " + at);
					nested.put(at, target);
				}
				if (node instanceof Parallel g)
					for (int m = 0; m < g.members().size(); m++) {
						var member = g.members().get(m);
						unresolved(member.nodes(), at.child("member", member.name(), m), selected, nested);
					}
				if (node instanceof Fan f)
					unresolved(f.body(), at.child("body", "item", 0), selected, nested);
				if (node instanceof Choice c)
					for (int a = 0; a < c.arms().size(); a++) {
						Arm arm = c.arms().get(a);
						unresolved(arm.nodes(), at.child("arm", arm.outcome().name(), a), selected, nested);
					}
			}
		}

	}

	/** Preserve each fact once, retaining component order and exact consumption links. */
	private static void recipe(Fact fact, Map<ValueId, ValueRecipe> values, TypeContracts contracts) {
		if (values.containsKey(fact.identity()))
			return;
		fact.components().forEach(f -> recipe(f, values, contracts));
		fact.consumed().forEach(f -> recipe(f, values, contracts));
		values.put(fact.identity(),
				new ValueRecipe(fact.identity(), fact.type(),
						fact.type() instanceof ProductType ? null : contracts.contract(fact.type()),
						fact.components().stream().map(Fact::identity).toList(),
						fact.consumed().stream().map(Fact::identity).toList()));
	}

	private static void valueIdentity(StringBuilder target, ValueId value) {
		IdentityEncoding.field(target, value.placement().graphName());
		IdentityEncoding.field(target, value.role());
		IdentityEncoding.field(target, value.phase());
	}

	private static void contractIdentity(StringBuilder target, TypeContracts.Contract contract) {
		IdentityEncoding.field(target, contract.javaType());
		IdentityEncoding.field(target, contract.shapeDigest());
	}

	/** Owned immutable construction snapshot, including the resolved finite duration. */
	public Definition<?, ?> definition() {
		return definition;
	}

	/**
	 * Authoritative semantic graph shared by runtime and inspection. A raw graph cannot
	 * start a durable run.
	 */
	public WorkflowGraph<?, ?> graph() {
		return graph;
	}

	public RegionSummary rootSummary() {
		return rootSummary;
	}

	public Set<Capability> capabilities() {
		return rootSummary.capabilities();
	}

	public String coordinateScheme() {
		return Coordinates.SCHEME;
	}

	public String compilerContract() {
		return COMPILER_CONTRACT;
	}

	public String authoredIdentity() {
		return authoredIdentity;
	}

	public TypeContracts.CodecIdentity codecIdentity() {
		return codec;
	}

	public TypeContracts.Contract inputContract() {
		return input;
	}

	public TypeContracts.Contract outputContract() {
		return output;
	}

	/** Immutable local associations for preparation, outside semantic graph data. */
	public Map<Placement, Step<?, ?>> suppliedSteps() {
		return suppliedSteps;
	}

	/** Validated children, isolated from the parent's value namespace. */
	public Map<Placement, ValidatedWorkflow> children() {
		return children;
	}

	/** Symbolic runtime fan-out declarations keyed by their graph fork identity. */
	public Map<String, Fan> fans() {
		Map<String, Fan> result = new LinkedHashMap<>();
		collectFans(definition.nodes(), Coordinates.root(definition), result);
		return Map.copyOf(result);
	}

	private static void collectFans(List<Node> nodes, Placement parent, Map<String, Fan> result) {
		for (int i = 0; i < nodes.size(); i++) {
			Node node = nodes.get(i);
			Placement at = Coordinates.node(parent, node, i);
			if (node instanceof Fan f) {
				result.put(at.graphName(), f);
				collectFans(f.body(), at.child("body", "item", 0), result);
			}
			else if (node instanceof Parallel p) {
				for (int m = 0; m < p.members().size(); m++) {
					var member = p.members().get(m);
					collectFans(member.nodes(), at.child("member", member.name(), m), result);
				}
			}
			else if (node instanceof Choice c) {
				for (int a = 0; a < c.arms().size(); a++) {
					var arm = c.arms().get(a);
					collectFans(arm.nodes(), at.child("arm", arm.outcome().name(), a), result);
				}
			}
		}
	}

	public List<Product> products() {
		return products;
	}

	/** Immutable compiler-selected convergence alternatives. */
	public List<Capture> captures() {
		return captures;
	}

	/** Immutable exact-source/assembly recipes keyed by logical value identity. */
	public Map<ValueId, ValueRecipe> values() {
		return values;
	}

	/**
	 * Convenience accessor for a definition with exactly one explicit terminal. Choices
	 * with multiple terminals use the terminal nodes on {@link #graph()}.
	 * @return the single explicit terminal
	 * @throws IllegalStateException if more than one terminal exists
	 */
	public WorkflowNode.TerminalNode terminal() {
		return graph.nodes()
			.stream()
			.filter(WorkflowNode.TerminalNode.class::isInstance)
			.map(WorkflowNode.TerminalNode.class::cast)
			.reduce((a, b) -> {
				throw new IllegalStateException("not a sequential terminal");
			})
			.orElseThrow();
	}

	public DeadlinePolicy deadlinePolicy() {
		return deadlinePolicy;
	}

	public Optional<java.time.Duration> authoredDuration() {
		return Optional.ofNullable(authoredDuration);
	}

	public String deadlineOrigin() {
		return deadlineOrigin;
	}

}
