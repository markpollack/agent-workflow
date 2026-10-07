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
 * during build.
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

		Sequence then(Step<?, ?> step);

		Sequence then(String label, Step<?, ?> step);

		Sequence subWorkflow(String label, ValidatedWorkflow workflow);

		/** Begin an ordinary choice whose Step returns a concrete enum. */
		Decision<AfterChoice> decision(String label, Step<?, ?> step);

		/**
		 * Begin native routing on the usable Verdict's Conclusion, retaining the full
		 * assessment.
		 */
		Decision<AfterChoice> verdict(String label, Step<?, Verdict> assessment);

		ParallelPolicy<Sequence> parallel(String label);

		Start maxDuration(Duration duration);

	}

	/** Append work, lexical choices or an explicit terminal. */
	public interface Sequence {

		Sequence then(Step<?, ?> step);

		Sequence then(String label, Step<?, ?> step);

		Sequence subWorkflow(String label, ValidatedWorkflow workflow);

		/** Begin an ordinary choice whose Step returns a concrete enum. */
		Decision<AfterChoice> decision(String label, Step<?, ?> step);

		/**
		 * Begin native routing on the usable Verdict's Conclusion, retaining the full
		 * assessment.
		 */
		Decision<AfterChoice> verdict(String label, Step<?, Verdict> assessment);

		ParallelPolicy<Sequence> parallel(String label);

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

		P end();

	}

	/**
	 * One isolated lexical arm. Empty continuing arms preserve incoming data.
	 * {@code when()} begins its sibling; {@code end()} returns to the enclosing stage.
	 *
	 * @param <P> enclosing stage
	 */
	public interface Arm<P> {

		Arm<P> then(Step<?, ?> step);

		Arm<P> then(String label, Step<?, ?> step);

		Arm<P> subWorkflow(String label, ValidatedWorkflow workflow);

		/** Begin an ordinary choice whose Step returns a concrete enum. */
		Decision<Arm<P>> decision(String label, Step<?, ?> step);

		/**
		 * Begin native routing on the usable Verdict's Conclusion, retaining the full
		 * assessment.
		 */
		Decision<Arm<P>> verdict(String label, Step<?, Verdict> assessment);

		ParallelPolicy<Arm<P>> parallel(String label);

		Arm<P> terminate(Terminal terminal);

		Arm<P> terminate(Terminal terminal, String reason);

		Arm<P> when(Enum<?> outcome);

		P end();

	}

	/** A static group requires its explicit settlement policy before branches. */
	public interface ParallelPolicy<P> {

		ParallelBranches<P> allSuccessful();

	}

	/** Declaration order defines member identity and aggregate order. */
	public interface ParallelBranches<P> {

		Branch<P> branch(String name);

	}

	/** Isolated member sequence; end closes the group, never the workflow. */
	public interface Branch<P> {

		Branch<P> then(Step<?, ?> step);

		Branch<P> then(String label, Step<?, ?> step);

		Branch<P> subWorkflow(String label, ValidatedWorkflow workflow);

		Decision<Branch<P>> decision(String label, Step<?, ?> step);

		Decision<Branch<P>> verdict(String label, Step<?, Verdict> step);

		ParallelPolicy<Branch<P>> parallel(String label);

		Branch<P> branch(String name);

		P end();

	}

	private static class Body {

		final List<Node> nodes = new ArrayList<>();

		final Map<Node, Step<?, ?>> supplied;

		final List<Type> results;

		Type first, current;

		boolean closed, blocked, inMember;

		Body(Map<Node, Step<?, ?>> supplied, List<Type> results, Type incoming) {
			this.supplied = supplied;
			this.results = results;
			current = incoming;
		}

		void open() {
			if (closed || blocked)
				throw new IllegalStateException("closed or unfinished lexical block");
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
				throw new IllegalStateException("a parallel member must reach its join");
			nodes.add(new End(terminal, reason));
			closed = true;
			if (terminal == Terminal.SUCCEEDED)
				results.add(current);
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
			super(new IdentityHashMap<>(), new ArrayList<>(), null);
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

		public ParallelPolicy<Sequence> parallel(String label) {
			return parallelBlock(label, (Sequence) this);
		}

		public Builder maxDuration(Duration duration) {
			open();
			if (this.duration != null)
				throw new IllegalStateException("duration already configured");
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
				throw new IllegalStateException("nonempty completed definition required");
			Type output = results.isEmpty() ? current : results.getFirst();
			if (output == null)
				output = first;
			var definition = new Definition<>(name, first, output, nodes, duration);
			var selected = new LinkedHashMap<Placement, Step<?, ?>>();
			collect(nodes, new Placement(List.of(new Segment("workflow", name, 0))), selected);
			return ValidatedWorkflow.compile(definition, selected);
		}

		void collect(List<Node> body, Placement parent, Map<Placement, Step<?, ?>> selected) {
			for (int i = 0; i < body.size(); i++) {
				Node node = body.get(i);
				String label = node instanceof Call c ? c.id() : node instanceof Choice c ? c.id()
						: node instanceof Parallel g ? g.id() : node instanceof Child c ? c.id() : "terminate";
				Placement at = parent.child("node", label, i);
				if (supplied.containsKey(node))
					selected.put(at, supplied.get(node));
				if (node instanceof Parallel g)
					for (int m = 0; m < g.members().size(); m++) {
						var member = g.members().get(m);
						collect(member.nodes(), at.child("member", member.name(), m), selected);
					}
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
				throw new IllegalStateException("closed or unfinished choice");
			if (!arms.isEmpty())
				arms.getLast().sealed = true;
			var arm = new ArmBuilder<>(this, Objects.requireNonNull(outcome));
			arms.add(arm);
			return arm;
		}

		public P end() {
			if (ended || arms.isEmpty() || arms.getLast().blocked)
				throw new IllegalStateException("empty, closed or unfinished choice");
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
			super(choice.body.supplied, choice.body.results, choice.body.current);
			this.choice = choice;
			this.outcome = outcome;
			inMember = choice.body.inMember;
		}

		@Override
		void open() {
			if (sealed)
				throw new IllegalStateException("stale arm");
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
				throw new IllegalStateException("stale arm");
			return choice.when(outcome);
		}

		public P end() {
			if (sealed)
				throw new IllegalStateException("stale arm");
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
				throw new IllegalStateException("group policy already set");
			policy = true;
			return this;
		}

		public Branch<P> branch(String name) {
			if (!policy || ended || !branches.isEmpty() && branches.getLast().blocked)
				throw new IllegalStateException("closed or unfinished parallel group");
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
				throw new IllegalStateException("empty, closed or unfinished parallel group");
			if (branches.stream().anyMatch(b -> b.nodes.isEmpty()))
				throw new IllegalArgumentException("empty branch");
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
					group.body.current == null ? group.body.first : group.body.current);
			this.group = group;
			this.name = name;
			inMember = true;
		}

		@Override
		void open() {
			if (sealed)
				throw new IllegalStateException("stale parallel branch");
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

		public ParallelPolicy<Branch<P>> parallel(String label) {
			return parallelBlock(label, (Branch<P>) this);
		}

		public Branch<P> branch(String name) {
			if (sealed)
				throw new IllegalStateException("stale parallel branch");
			return group.branch(name);
		}

		public P end() {
			if (sealed)
				throw new IllegalStateException("stale parallel branch");
			return group.end();
		}

	}

}
