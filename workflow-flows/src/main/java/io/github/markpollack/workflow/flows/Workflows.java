package io.github.markpollack.workflow.flows;

import java.lang.reflect.Type;
import io.github.markpollack.judge.verdict.Verdict;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Readable ordered authoring through the shared structural and binding compiler. Inputs
 * and outputs derive from each supplied Step's concrete generic declaration. A decision
 * requires one arm per enum constant; a native verdict requires one arm per
 * {@link Verdict.Conclusion}. {@code end()} closes a lexical choice, while explicit
 * terminals close execution paths. Continuing arms expose only compiler-checked captures.
 * Parallel branches are isolated sequences requiring explicit allSuccessful settlement;
 * end closes a group and returns to its enclosing sequence. No application code executes
 * during build. Runtime forEach saves ordered typed membership and requires explicit
 * positive item and logical occupancy bounds.
 */
public final class Workflows {

	private Workflows() {
	}

	/**
	 * Begin a definition with an authored name used in its stable placement identity.
	 * @param name nonblank authored name
	 * @return initial stage requiring work before a terminal
	 */
	public static Start define(String name) {
		return new Builder(name);
	}

	/** Initial nonempty definition stage. */
	public interface Start {

		/** Append an application-supplied Step; its concrete types derive the input binding. */
		Sequence then(Step<?, ?> step);

		/** Append a Step with a stable authored label for diagnostics and invocation identity. */
		Sequence then(String label, Step<?, ?> step);

		/** Invoke a reusable child; only its declared result returns to this enclosing scope. */
		Sequence subWorkflow(String label, ValidatedWorkflow workflow);

		/** Begin an ordinary choice whose Step returns a concrete enum. */
		Decision<AfterChoice> decision(String label, Step<?, ?> step);

		/**
		 * Begin native routing on the usable Verdict's Conclusion, retaining the full
		 * assessment.
		 */
		Decision<AfterChoice> verdict(String label, Step<?, Verdict> assessment);

		ParallelPolicy<Sequence> parallel(String label);

		FanItems<Sequence> forEach(String label);

		Start maxDuration(Duration duration);

	}

	/** Append work, lexical choices or an explicit terminal; build validates all paths. */
	public interface Sequence {

		/**
		 * Validate and freeze this definition without adding a terminal. Ordinary returning
		 * paths still require {@code terminate(...)}. A final child that always fails or
		 * cancels already closes its path and needs no unreachable parent terminal.
		 * @return immutable workflow ready for registration and execution
		 * @throws IllegalArgumentException for a dangling path, unreachable successor,
		 * ambiguous input, unsupported construct or inconsistent result contract
		 * @throws IllegalStateException if an inner lexical block remains open
		 */
		ValidatedWorkflow build();

		/** Append an application-supplied Step; its concrete types derive the input binding. */
		Sequence then(Step<?, ?> step);

		/** Append a Step with a stable authored label for diagnostics and invocation identity. */
		Sequence then(String label, Step<?, ?> step);

		/** Invoke a reusable child; only its declared result returns to this enclosing scope. */
		Sequence subWorkflow(String label, ValidatedWorkflow workflow);

		/** Begin an ordinary choice whose Step returns a concrete enum. */
		Decision<AfterChoice> decision(String label, Step<?, ?> step);

		/**
		 * Begin native routing on the usable Verdict's Conclusion, retaining the full
		 * assessment.
		 */
		Decision<AfterChoice> verdict(String label, Step<?, Verdict> assessment);

		ParallelPolicy<Sequence> parallel(String label);

		FanItems<Sequence> forEach(String label);

		Sequence maxDuration(Duration duration);

		Closed terminate(Terminal terminal);

		Closed terminate(Terminal terminal, String reason);

	}

	/** A closed lexical choice; build requires every normal path to terminate. */
	public interface AfterChoice extends Sequence {

		ValidatedWorkflow build();

	}

	/** Explicitly closed definition; build checks every path and typed binding. */
	public interface Closed {

		ValidatedWorkflow build();

	}

	/**
	 * Exhaustive enum choice; end closes its lexical block.
	 *
	 * @param <P> enclosing stage
	 */
	public interface Decision<P> {

		Arm<P> when(Enum<?> outcome);

		/** Close this lexical block and return to its enclosing stage; does not add a terminal. */
		P end();

	}

	/**
	 * One isolated lexical arm. Empty continuing arms preserve incoming data.
	 * {@code when()} begins its sibling; {@code end()} returns to the enclosing stage.
	 *
	 * @param <P> enclosing stage
	 */
	public interface Arm<P> {

		/** Append an application-supplied Step; its concrete types derive the input binding. */
		Arm<P> then(Step<?, ?> step);

		/** Append a Step with a stable authored label for diagnostics and invocation identity. */
		Arm<P> then(String label, Step<?, ?> step);

		/** Invoke a reusable child; only its declared result returns to this enclosing scope. */
		Arm<P> subWorkflow(String label, ValidatedWorkflow workflow);

		/** Begin an ordinary choice whose Step returns a concrete enum. */
		Decision<Arm<P>> decision(String label, Step<?, ?> step);

		/**
		 * Begin native routing on the usable Verdict's Conclusion, retaining the full
		 * assessment.
		 */
		Decision<Arm<P>> verdict(String label, Step<?, Verdict> assessment);

		ParallelPolicy<Arm<P>> parallel(String label);

		FanItems<Arm<P>> forEach(String label);

		Arm<P> terminate(Terminal terminal);

		Arm<P> terminate(Terminal terminal, String reason);

		Arm<P> when(Enum<?> outcome);

		/** Close this lexical block and return to its enclosing stage; does not add a terminal. */
		P end();

	}

	/** A static group requires its explicit settlement policy before branches. */
	public interface ParallelPolicy<P> {

		/**
		 * Require every branch to return successfully before joining. A negative business
		 * assessment is still a returned value; an execution failure prevents the join.
		 * Admitted work settles before failure is reported.
		 * @return stage requiring at least one named branch
		 */
		ParallelBranches<P> allSuccessful();

	}

	/** Declaration order defines member identity and aggregate order. */
	public interface ParallelBranches<P> {

		Branch<P> branch(String name);

	}

	/** Isolated member sequence; end closes the group, never the workflow. */
	public interface Branch<P> {

		/** Append an application-supplied Step; its concrete types derive the input binding. */
		Branch<P> then(Step<?, ?> step);

		/** Append a Step with a stable authored label for diagnostics and invocation identity. */
		Branch<P> then(String label, Step<?, ?> step);

		/** Invoke a reusable child; only its declared result returns to this enclosing scope. */
		Branch<P> subWorkflow(String label, ValidatedWorkflow workflow);

		Decision<Branch<P>> decision(String label, Step<?, ?> step);

		Decision<Branch<P>> verdict(String label, Step<?, Verdict> step);

		ParallelPolicy<Branch<P>> parallel(String label);

		FanItems<Branch<P>> forEach(String label);

		Branch<P> branch(String name);

		/** Close this lexical block and return to its enclosing stage; does not add a terminal. */
		P end();

	}

	/** Runtime membership requires explicit cardinality and logical occupancy bounds. */
	public interface FanItems<P> {

		/**
		 * Bound the accepted manifest before any item effects. Oversized input fails;
		 * it is never truncated. Empty input produces an empty result List.
		 * @param maximum positive maximum number of input occurrences
		 * @return logical occupancy stage
		 */
		FanFlight<P> maxItems(int maximum);

	}

	public interface FanFlight<P> {

		/**
		 * Bound admitted, unsettled item workflows. A waiting item retains its slot.
		 * Physical Step concurrency is configured separately on the runtime.
		 * @param maximum positive logical item capacity
		 * @return settlement policy stage
		 */
		FanPolicy<P> maxInFlight(int maximum);

	}

	public interface FanPolicy<P> {

		/**
		 * Join only after every manifest item returns a result; execution failures cannot
		 * produce a successful partial List. Negative assessments remain values.
		 * @return stage for authoring one item body
		 */
		FanBody<P> allSuccessful();

	}

	/**
	 * One typed item body; end returns its ordered List result to the enclosing sequence.
	 * Each input occurrence has isolated state and stable manifest-index identity, including
	 * equal values. Results retain manifest order regardless of completion order.
	 * Recovery reuses accepted membership and committed results; unresolved effects may repeat.
	 */
	public interface FanBody<P> {

		/** Append an application-supplied Step; its concrete types derive the input binding. */
		FanBody<P> then(Step<?, ?> step);

		/** Append a Step with a stable authored label for diagnostics and invocation identity. */
		FanBody<P> then(String label, Step<?, ?> step);

		/** Invoke a reusable child; only its declared result returns to this enclosing scope. */
		FanBody<P> subWorkflow(String label, ValidatedWorkflow workflow);

		Decision<FanBody<P>> decision(String label, Step<?, ?> step);

		Decision<FanBody<P>> verdict(String label, Step<?, Verdict> step);

		ParallelPolicy<FanBody<P>> parallel(String label);

		FanItems<FanBody<P>> forEach(String label);

		/** Close this lexical block and return to its enclosing stage; does not add a terminal. */
		P end();

	}

	private static class Body {

		final List<Node> nodes = new ArrayList<>();

		final Map<Node, Step<?, ?>> supplied;

		final List<Type> results;

		final String location;

		Type first, current;

		boolean closed, blocked, inMember;

		Body(Map<Node, Step<?, ?>> supplied, List<Type> results, Type incoming, String location) {
			this.supplied = supplied;
			this.results = results;
			current = incoming;
			this.location = location;
		}

		void open() {
			if (closed)
				throw new IllegalStateException(at("closed lexical block", "do not append work after a terminal"));
			if (blocked)
				throw new IllegalStateException(at("unfinished lexical block", "call end() on the innermost block first"));
		}

		String at(String problem, String correction) {
			return location + ": " + problem + "; " + correction;
		}

		void call(String label, Step<?, ?> step) {
			open();
			var types = StepTypes.of(Objects.requireNonNull(step).getClass());
			var node = new Call(label, Op.declared(label, types.input(), types.output()));
			nodes.add(node);
			supplied.put(node, step);
			if (first == null)
				first = types.input();
			current = types.output();
		}

		void child(String label, ValidatedWorkflow workflow) {
			open();
			nodes.add(new Child(label, workflow));
			if (first == null)
				first = workflow.definition().input();
			current = workflow.definition().output();
		}

		void terminal(Terminal terminal, String reason) {
			open();
			if (inMember)
				throw new IllegalStateException(at("a member/item body must reach its join", "return an item/branch result and close the group with end()"));
			nodes.add(new End(terminal, reason));
			closed = true;
			if (terminal == Terminal.SUCCEEDED)
				results.add(current);
		}

		<P> FanBuilder<P> fanBlock(String label, P parent) {
			open();
			blocked = true;
			return new FanBuilder<>(this, parent, label);
		}

		<P> ParallelBuilder<P> parallelBlock(String label, P parent) {
			open();
			blocked = true;
			return new ParallelBuilder<>(this, parent, label);
		}

		<P> ChoiceBuilder<P> nativeChoice(String label, Step<?, Verdict> step, P parent) {
			open();
			var types = StepTypes.of(Objects.requireNonNull(step).getClass());
			if (types.output() != Verdict.class)
				throw new IllegalArgumentException("native Verdict output required");
			if (first == null)
				first = types.input();
			if (current == null && nodes.isEmpty())
				current = types.input();
			blocked = true;
			return new ChoiceBuilder<>(this, parent, label, step, types, true);
		}

		<P> ChoiceBuilder<P> choice(String label, Step<?, ?> step, P parent) {
			open();
			var types = StepTypes.of(Objects.requireNonNull(step).getClass());
			if (first == null)
				first = types.input();
			if (current == null && nodes.isEmpty())
				current = types.input();
			blocked = true;
			return new ChoiceBuilder<>(this, parent, label, step, types, false);
		}

	}

	private static final class Builder extends Body implements Start, Sequence, AfterChoice, Closed {

		final String name;

		Duration duration;

		Builder(String name) {
			super(new IdentityHashMap<>(), new ArrayList<>(), null, "workflow '" + name + "'");
			this.name = Objects.requireNonNull(name);
		}

		public Builder then(Step<?, ?> step) {
			return then("step-" + (nodes.size() + 1), step);
		}

		public Builder then(String label, Step<?, ?> step) {
			call(label, step);
			return this;
		}

		public Builder subWorkflow(String label, ValidatedWorkflow workflow) {
			child(label, workflow);
			return this;
		}

		public Decision<AfterChoice> decision(String label, Step<?, ?> step) {
			return choice(label, step, (AfterChoice) this);
		}

		public Decision<AfterChoice> verdict(String label, Step<?, Verdict> step) {
			return nativeChoice(label, step, (AfterChoice) this);
		}

		public FanItems<Sequence> forEach(String label) {
			return fanBlock(label, (Sequence) this);
		}

		public ParallelPolicy<Sequence> parallel(String label) {
			return parallelBlock(label, (Sequence) this);
		}

		public Builder maxDuration(Duration duration) {
			open();
			if (this.duration != null)
				throw new IllegalStateException(at("duration already configured", "set maxDuration once"));
			this.duration = Objects.requireNonNull(duration);
			return this;
		}

		public Builder terminate(Terminal terminal) {
			return terminate(terminal, "");
		}

		public Builder terminate(Terminal terminal, String reason) {
			terminal(terminal, reason);
			return this;
		}

		public ValidatedWorkflow build() {
			if (blocked || first == null)
				throw new IllegalStateException(at("nonempty completed definition required", "add work and close each inner block with end() before build()"));
			Type output = results.isEmpty() ? current : results.getFirst();
			if (output == null)
				output = first;
			var definition = new Definition<>(name, first, output, nodes, duration);
			var selected = new LinkedHashMap<Placement, Step<?, ?>>();
			collect(nodes, new Placement(List.of(new Segment("workflow", name, 0))), selected);
			return ValidatedWorkflow.compileInferred(definition, selected);
		}

		void collect(List<Node> body, Placement parent, Map<Placement, Step<?, ?>> selected) {
			for (int i = 0; i < body.size(); i++) {
				Node node = body.get(i);
				String label = node instanceof Call c ? c.id()
						: node instanceof Choice c ? c.id() : node instanceof Parallel g ? g.id()
								: node instanceof Fan f ? f.id() : node instanceof Child c ? c.id() : "terminate";
				Placement at = parent.child("node", label, i);
				if (supplied.containsKey(node))
					selected.put(at, supplied.get(node));
				if (node instanceof Parallel g)
					for (int m = 0; m < g.members().size(); m++) {
						var member = g.members().get(m);
						collect(member.nodes(), at.child("member", member.name(), m), selected);
					}
				if (node instanceof Fan f)
					collect(f.body(), at.child("body", "item", 0), selected);
				if (node instanceof Choice c)
					for (int a = 0; a < c.arms().size(); a++) {
						var arm = c.arms().get(a);
						collect(arm.nodes(), at.child("arm", arm.outcome().name(), a), selected);
					}
			}
		}

	}

	private static final class ChoiceBuilder<P> implements Decision<P> {

		final Body body;

		final P parent;

		final String label;

		final Step<?, ?> step;

		final StepTypes types;

		final boolean nativeAssessment;

		final List<ArmBuilder<P>> arms = new ArrayList<>();

		boolean ended;

		ChoiceBuilder(Body body, P parent, String label, Step<?, ?> step, StepTypes types, boolean nativeAssessment) {
			this.body = body;
			this.parent = parent;
			this.label = label;
			this.step = step;
			this.types = types;
			this.nativeAssessment = nativeAssessment;
		}

		public Arm<P> when(Enum<?> outcome) {
			if (ended || (!arms.isEmpty() && arms.getLast().blocked))
				throw new IllegalStateException(body.at("decision '" + label + "': closed or unfinished choice", "close an inner block with end(); do not reuse a closed choice"));
			if (!arms.isEmpty())
				arms.getLast().sealed = true;
			var arm = new ArmBuilder<>(this, Objects.requireNonNull(outcome,
					body.at("decision '" + label + "': null choice outcome", "use a constant from the declared decision enum")));
			arms.add(arm);
			return arm;
		}

		public P end() {
			if (ended || arms.isEmpty() || arms.getLast().blocked)
				throw new IllegalStateException(body.at("decision '" + label + "': empty, closed or unfinished choice", "declare an arm for every outcome and close the innermost block before end()"));
			ended = true;
			arms.forEach(a -> a.sealed = true);
			var node = new Choice(label, nativeAssessment ? null : Op.declared(label, types.input(), types.output()),
					nativeAssessment ? new Assessment<>(label, types.input()) : null,
					arms.stream().map(a -> new WorkflowModel.Arm(a.outcome, List.copyOf(a.nodes))).toList());
			body.nodes.add(node);
			body.supplied.put(node, step);
			body.blocked = false;
			var continuing = arms.stream().filter(a -> !a.closed).toList();
			if (continuing.isEmpty())
				body.closed = true;
			else if (continuing.stream().map(a -> a.current).distinct().count() == 1)
				body.current = continuing.getFirst().current;
			else
				body.current = null;
			return parent;
		}

	}

	private static final class ArmBuilder<P> extends Body implements Arm<P> {

		final ChoiceBuilder<P> choice;

		final Enum<?> outcome;

		boolean sealed;

		ArmBuilder(ChoiceBuilder<P> choice, Enum<?> outcome) {
			super(choice.body.supplied, choice.body.results, choice.body.current,
					choice.body.location + " / decision '" + choice.label + "' / arm " + outcome);
			this.choice = choice;
			this.outcome = outcome;
			inMember = choice.body.inMember;
		}

		@Override
		void open() {
			if (sealed)
				throw new IllegalStateException(at("stale arm", "use the current arm handle; a sibling when() or end() closes this arm"));
			super.open();
		}

		public Arm<P> then(Step<?, ?> step) {
			return then("step-" + (nodes.size() + 1), step);
		}

		public Arm<P> then(String label, Step<?, ?> step) {
			call(label, step);
			return this;
		}

		public Arm<P> subWorkflow(String label, ValidatedWorkflow workflow) {
			child(label, workflow);
			return this;
		}

		public Decision<Arm<P>> decision(String label, Step<?, ?> step) {
			return choice(label, step, (Arm<P>) this);
		}

		public Decision<Arm<P>> verdict(String label, Step<?, Verdict> step) {
			return nativeChoice(label, step, (Arm<P>) this);
		}

		public FanItems<Arm<P>> forEach(String label) {
			return fanBlock(label, (Arm<P>) this);
		}

		public ParallelPolicy<Arm<P>> parallel(String label) {
			return parallelBlock(label, (Arm<P>) this);
		}

		public Arm<P> terminate(Terminal terminal) {
			return terminate(terminal, "");
		}

		public Arm<P> terminate(Terminal terminal, String reason) {
			terminal(terminal, reason);
			return this;
		}

		public Arm<P> when(Enum<?> outcome) {
			if (sealed)
				throw new IllegalStateException(at("stale arm", "use the current arm handle; a sibling when() or end() closes this arm"));
			return choice.when(outcome);
		}

		public P end() {
			if (sealed)
				throw new IllegalStateException(at("stale arm", "use the current arm handle; a sibling when() or end() closes this arm"));
			return choice.end();
		}

	}

	private static final class ParallelBuilder<P> implements ParallelPolicy<P>, ParallelBranches<P> {

		final Body body;

		final P parent;

		final String label;

		final List<BranchBuilder<P>> branches = new ArrayList<>();

		boolean policy, ended;

		ParallelBuilder(Body body, P parent, String label) {
			this.body = body;
			this.parent = parent;
			this.label = Objects.requireNonNull(label);
		}

		public ParallelBranches<P> allSuccessful() {
			if (policy || ended)
				throw new IllegalStateException(body.at("parallel '" + label + "': group policy already set", "call allSuccessful() once before declaring branches"));
			policy = true;
			return this;
		}

		public Branch<P> branch(String name) {
			if (!policy || ended || !branches.isEmpty() && branches.getLast().blocked)
				throw new IllegalStateException(body.at("parallel '" + label + "': closed or unfinished parallel group", "close the innermost block before branch(); do not reuse a closed group"));
			if (!branches.isEmpty()) {
				branches.getLast().sealed = true;
				if (body.first == null)
					body.first = branches.getFirst().first;
			}
			var branch = new BranchBuilder<>(this, Objects.requireNonNull(name));
			branches.add(branch);
			return branch;
		}

		P end() {
			if (ended || branches.isEmpty() || branches.getLast().blocked)
				throw new IllegalStateException(body.at("parallel '" + label + "': empty, closed or unfinished parallel group", "declare nonempty branches and close inner blocks before end()"));
			if (branches.stream().anyMatch(b -> b.nodes.isEmpty()))
				throw new IllegalArgumentException(body.at("parallel '" + label + "': empty branch", "add work to every branch before end()"));
			ended = true;
			branches.forEach(b -> b.sealed = true);
			body.nodes.add(new Parallel(label, null, true,
					branches.stream().map(b -> new Member(b.name, List.copyOf(b.nodes))).toList()));
			if (body.first == null)
				body.first = branches.getFirst().first;
			List<Type> roles = branches.stream().map(b -> b.current).toList();
			body.current = roles.stream().distinct().count() == 1 ? new ListType(roles.getFirst())
					: new ProductType(roles);
			body.blocked = false;
			return parent;
		}

	}

	private static final class BranchBuilder<P> extends Body implements Branch<P> {

		final ParallelBuilder<P> group;

		final String name;

		boolean sealed;

		BranchBuilder(ParallelBuilder<P> group, String name) {
			super(group.body.supplied, group.body.results,
					group.body.current == null ? group.body.first : group.body.current,
					group.body.location + " / parallel '" + group.label + "' / branch '" + name + "'");
			this.group = group;
			this.name = name;
			inMember = true;
		}

		@Override
		void open() {
			if (sealed)
				throw new IllegalStateException(at("stale parallel branch", "use the current branch handle; branch() or end() closes the preceding branch"));
			super.open();
		}

		public Branch<P> then(Step<?, ?> step) {
			return then("step-" + (nodes.size() + 1), step);
		}

		public Branch<P> then(String label, Step<?, ?> step) {
			call(label, step);
			return this;
		}

		public Branch<P> subWorkflow(String label, ValidatedWorkflow workflow) {
			child(label, workflow);
			return this;
		}

		public Decision<Branch<P>> decision(String label, Step<?, ?> step) {
			return choice(label, step, (Branch<P>) this);
		}

		public Decision<Branch<P>> verdict(String label, Step<?, Verdict> step) {
			return nativeChoice(label, step, (Branch<P>) this);
		}

		public FanItems<Branch<P>> forEach(String label) {
			return fanBlock(label, (Branch<P>) this);
		}

		public ParallelPolicy<Branch<P>> parallel(String label) {
			return parallelBlock(label, (Branch<P>) this);
		}

		public Branch<P> branch(String name) {
			if (sealed)
				throw new IllegalStateException(at("stale parallel branch", "use the current branch handle; branch() or end() closes the preceding branch"));
			return group.branch(name);
		}

		public P end() {
			if (sealed)
				throw new IllegalStateException(at("stale parallel branch", "use the current branch handle; branch() or end() closes the preceding branch"));
			return group.end();
		}

	}

	private static final class FanBuilder<P> extends Body
			implements FanItems<P>, FanFlight<P>, FanPolicy<P>, FanBody<P> {

		final Body enclosing;

		final P parent;

		final String label;

		final Type element;

		int items, flight;

		boolean policy, ended;

		FanBuilder(Body enclosing, P parent, String label) {
			super(enclosing.supplied, enclosing.results, null, enclosing.location + " / forEach '" + label + "'");
			this.enclosing = enclosing;
			this.parent = parent;
			this.label = Objects.requireNonNull(label);
			Type feeder = enclosing.current == null ? enclosing.first : enclosing.current;
			this.element = feeder instanceof java.lang.reflect.ParameterizedType t && t.getRawType() == List.class
					? t.getActualTypeArguments()[0] : null;
			current = element;
			inMember = true;
		}

		@Override
		void open() {
			if (ended || !policy)
				throw new IllegalStateException(at("closed or unconfigured fan-out body", "set positive bounds and allSuccessful() before work; do not reuse after end()"));
			super.open();
		}

		public FanFlight<P> maxItems(int maximum) {
			if (items != 0 || maximum <= 0)
				throw new IllegalArgumentException(at("positive maxItems required once, received " + maximum, "set maxItems to a positive item-count bound exactly once"));
			items = maximum;
			return this;
		}

		public FanPolicy<P> maxInFlight(int maximum) {
			if (items == 0 || flight != 0 || maximum <= 0)
				throw new IllegalArgumentException(at("positive maxInFlight required once, received " + maximum, "set maxItems first, then a positive maxInFlight bound exactly once"));
			flight = maximum;
			return this;
		}

		public FanBody<P> allSuccessful() {
			if (flight == 0 || policy)
				throw new IllegalStateException(at("fan-out policy requires bounds once", "set maxItems and maxInFlight, then call allSuccessful() once"));
			policy = true;
			return this;
		}

		public FanBody<P> then(Step<?, ?> step) {
			return then("step-" + (nodes.size() + 1), step);
		}

		public FanBody<P> then(String label, Step<?, ?> step) {
			call(label, step);
			return this;
		}

		public FanBody<P> subWorkflow(String label, ValidatedWorkflow workflow) {
			child(label, workflow);
			return this;
		}

		public Decision<FanBody<P>> decision(String label, Step<?, ?> step) {
			return choice(label, step, (FanBody<P>) this);
		}

		public Decision<FanBody<P>> verdict(String label, Step<?, Verdict> step) {
			return nativeChoice(label, step, (FanBody<P>) this);
		}

		public ParallelPolicy<FanBody<P>> parallel(String label) {
			return parallelBlock(label, (FanBody<P>) this);
		}

		public FanItems<FanBody<P>> forEach(String label) {
			return fanBlock(label, (FanBody<P>) this);
		}

		public P end() {
			open();
			if (nodes.isEmpty())
				throw new IllegalArgumentException(at("nonempty fan-out body required", "add a Step or subWorkflow before end()"));
			Type item = enclosing.first == null ? (element == null ? first : element) : null;
			if (current == null)
				throw new IllegalArgumentException(at("concrete item and result types required", "declare one typed item result on every continuing body path"));
			ended = true;
			enclosing.nodes.add(new Fan(label, item, items, flight, true, List.copyOf(nodes)));
			if (enclosing.first == null)
				enclosing.first = new ListType(item);
			enclosing.current = new ListType(current);
			enclosing.blocked = false;
			return parent;
		}

	}

}
