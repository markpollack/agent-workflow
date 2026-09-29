package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;

/**
 * Vocabulary shared by programmatic workflow construction and definition analysis.
 * {@link Definition}, {@link Call}, {@link Op} and the other {@link Node} records
 * describe authored control flow. They contain declarations, not application Step
 * instances. An ordered node list determines the sequence; a registration map does not.
 * <p>
 * The same container also exposes compiler facts such as {@link Binding}, {@link Capture}
 * and {@link RegionSummary}. Those are analysis results, not additional authoring
 * instructions or a way to supply unchecked runtime bindings. {@link Compilation} is
 * useful for inspecting analysis, but only {@link ValidatedWorkflow} is an execution
 * admission input.
 * <p>
 * Construction records do not defensively copy their supplied lists or validate their
 * contents. Each public compile entry point takes an owned, recursively immutable
 * snapshot before semantic analysis; callers must not mutate construction data during
 * that operation. Nothing in this model represents a running workflow, persists progress
 * or executes application steps. It is not a portable execution format.
 */
public final class WorkflowModel {

	private WorkflowModel() {
	}

	public enum Terminal {

		SUCCEEDED, FAILED, CANCELLED

	}

	public enum LimitPolicy {

		FAIL, EXIT

	}

	public enum LoopExit {

		TEST_TRUE, CAP_REACHED, LIMIT_FAILURE, CONTINUE

	}

	/**
	 * Explicit full-Type witness for lower-level construction, including parameterized
	 * records and lists. Use a direct concrete subclass; inherited unresolved witnesses
	 * refuse. Application-facing builders can instead obtain these declarations from a
	 * concrete Step implementation, without asking authors to repeat its type arguments.
	 */
	public abstract static class TypeRef<T> {

		public final Type type() {
			Type parent = getClass().getGenericSuperclass();
			if (!(parent instanceof ParameterizedType p) || p.getRawType() != TypeRef.class)
				throw new IllegalArgumentException("direct concrete TypeRef subclass required");
			return p.getActualTypeArguments()[0];
		}

	}

	/**
	 * A named input/output declaration, not an executable implementation. A {@link Call}
	 * places this declaration in a definition; executable preparation separately selects
	 * a registered application object for that placement. Constructing an Op neither
	 * verifies that object exists nor establishes a legal input binding.
	 */
	public static final class Op<I, O> {

		private final String name;

		private final Type input;

		private final Type output;

		private Op(String name, Type input, Type output) {
			this.name = name;
			this.input = input;
			this.output = output;
		}

		public String name() {
			return name;
		}

		public Type input() {
			return input;
		}

		public Type output() {
			return output;
		}

		/**
		 * Use already discovered full Types, for example from an executable selection.
		 * Definition validation still checks concrete types, supported value shapes and
		 * legal bindings; these declarations are not a validation bypass.
		 */
		public static Op<?, ?> declared(String name, Type input, Type output) {
			return new Op<>(name, input, output);
		}

		public static <I, O> Op<I, O> named(String name, Class<I> input, Class<O> output) {
			return new Op<>(name, input, output);
		}

		public static <I, O> Op<I, O> named(String name, TypeRef<I> input, TypeRef<O> output) {
			return new Op<>(name, input.type(), output.type());
		}

		public static <I, O> Op<I, O> named(String name, Class<I> input, TypeRef<O> output) {
			return new Op<>(name, input, output.type());
		}

		public static <I, O> Op<I, O> named(String name, TypeRef<I> input, Class<O> output) {
			return new Op<>(name, input.type(), output);
		}

	}

	public record Assessment<I>(String name, Type input) {
	}

	/**
	 * An authored workflow with an ordered body and declared boundary types. The node
	 * list can be assembled programmatically or by a fluent facade. Its generic
	 * parameters do not make arbitrary node lists safe: building a ValidatedWorkflow
	 * performs structural, binding and codec validation separately from javac.
	 *
	 * @param name stable authored name used in placement and compatibility identity
	 * @param input full root input declaration
	 * @param output full successful result declaration
	 * @param nodes ordered construction data; copied recursively by validation
	 * @param deadline authored duration, or null for the ValidatedWorkflow policy
	 * default; raw StructuredWorkflowCompiler analysis requires a resolved finite
	 * duration
	 */
	public record Definition<I, O>(String name, Type input, Type output, List<Node> nodes, Duration deadline) {
	}

	/**
	 * An authored control-flow occurrence. Availability here means the analyzer knows the
	 * construct, not that a particular runtime admits it. Sequential admission currently
	 * accepts Call and End; other constructs require separate runtime support.
	 */
	public sealed interface Node permits Call, Choice, Parallel, Fan, Loop, Child, Timer, End {

	}

	/**
	 * One occurrence of a step declaration. The id must be unique within its containing
	 * sequence; two uses of the same registered object need distinct occurrence IDs.
	 * Compiler placements also include the containing path and declaration ordinal.
	 *
	 * @param id stable authored occurrence name, independent of implementation lookup
	 * @param operation required input/output declaration for this occurrence
	 */
	public record Call(String id, Op<?, ?> operation) implements Node {
	}

	public record Choice(String id, Op<?, ?> operation, Assessment<?> assessment, List<Arm> arms) implements Node {
	}

	public record Arm(Enum<?> outcome, List<Node> nodes) {
	}

	public record Parallel(String id, Type output, boolean allSuccessful, List<Member> members) implements Node {
	}

	public record Member(String name, List<Node> nodes) {
	}

	public record Fan(String id, Type element, int maxItems, int maxInFlight, boolean allSuccessful,
			List<Node> body) implements Node {
	}

	public record Loop(String id, Op<?, Boolean> test, int maxIterations, LimitPolicy policy,
			List<Node> body) implements Node {
	}

	public record Child(String id, Definition<?, ?> definition) implements Node {
	}

	public record Timer(String id, Duration duration) implements Node {
	}

	/**
	 * Explicit terminal intent. SUCCEEDED must have the definition's declared current
	 * result; FAILED and CANCELLED require a nonblank reason. Validation refuses work
	 * after a terminal. The enclosing runtime determines the durable terminal transition.
	 */
	public record End(Terminal terminal, String reason) implements Node {
	}

	/** Required compiler constructs; runtime support is admitted separately. */
	public enum Capability {

		OPERATION, TERMINAL, DECISION, VERDICT, PARALLEL, FAN, LOOP, CHILD, TIMER

	}

	/** Analysis templates are separate from runtime invocation coordinates. */
	public record SummaryKey(Placement placement, String phase) {
	}

	/** Authoritative authored effects and result of one node or lexical sequence. */
	public record RegionSummary(Placement placement, String phase, boolean continues, Fact result,
			List<Placement> normalExits, java.util.Set<Terminal> terminals, List<Capture> captures,
			List<Product> products, java.util.Set<Capability> capabilities, Map<String, String> bounds) {
		public RegionSummary {
			normalExits = List.copyOf(normalExits);
			terminals = java.util.Set.copyOf(terminals);
			captures = List.copyOf(captures);
			products = List.copyOf(products);
			capabilities = java.util.Set.copyOf(capabilities);
			bounds = Map.copyOf(bounds);
			if (!continues && !normalExits.isEmpty())
				throw new IllegalArgumentException("terminal region has normal exits");
		}

		public Fact requireNormalResult(String context) {
			if (!continues || result == null)
				throw new IllegalArgumentException(context + ": normal result required");
			return result;
		}
	}

	public record Segment(String kind, String label, int ordinal) {
	}

	/**
	 * Immutable authored location: workflow name and nested labels/ordinals. This is
	 * shared by analysis and graph construction, and later combined with a run identity
	 * for a logical invocation. It is neither a registration ID nor an execution attempt.
	 * Rebuilding unchanged construction data produces the same placement.
	 * @param segments ordered path from workflow root to this authored position; copied
	 * on construction, with each segment retaining its label and sibling ordinal
	 */
	public record Placement(List<Segment> segments) {
		public Placement {
			segments = List.copyOf(segments);
		}

		public Placement child(String kind, String label, int ordinal) {
			List<Segment> next = new ArrayList<>(segments);
			next.add(new Segment(kind, label, ordinal));
			return new Placement(next);
		}

		public String graphName() {
			StringBuilder result = new StringBuilder();
			for (Segment s : segments)
				result.append(s.kind().length())
					.append(':')
					.append(s.kind())
					.append(s.label().length())
					.append(':')
					.append(s.label())
					.append('#')
					.append(s.ordinal())
					.append(';');
			return result.toString();
		}
	}

	/**
	 * Logical value identity, including its producer location, role and analysis phase.
	 * It describes a definition-level value; runtime invocation coordinates distinguish
	 * executions of that definition.
	 * @param placement authored location that introduces the value
	 * @param role the value's purpose, such as root, input, output or capture
	 * @param phase analysis context, such as root, root/first or root/carried; not an
	 * attempt number or a runtime iteration coordinate
	 */
	public record ValueId(Placement placement, String role, String phase) {
	}

	/**
	 * Immutable descriptive evidence about one value selected by analysis. Graph edges
	 * determine what executes next; {@link Binding bindings} determine which exact values
	 * an execution consumes and produces. A Fact contains neither a business payload nor
	 * a mutable context entry. Its {@link ValueId} distinguishes producers with the same
	 * Java type; display text is for explanation, not value lookup.
	 * <p>
	 * Components describe ordered record assembly, product membership or a carried
	 * value wrapped by loop analysis. Consumed facts
	 * describe dependency lineage: for example, an operation's output consumes its
	 * selected input. Analysis uses both relationships to prove provenance and state
	 * replacement; consumption does not delete an earlier value. Capture alternatives
	 * are recorded separately by {@link Capture}. Runtime value recipes retain exact
	 * identities and provenance derived from these facts.
	 * @param identity producer {@link Placement}, role and analysis phase of this value
	 * @param display human-readable description, such as {@code normalize.out}; not a
	 * unique identity or runtime lookup key
	 * @param type complete reflective type, including concrete generic arguments;
	 * analysis may also describe an internal product of member types
	 * @param components component facts in assembly/product order, a carried-state source, or
	 * empty for a whole value; record assembly follows canonical constructor order
	 * @param consumed input or prior-state facts establishing dependency/provenance,
	 * distinct from ordered assembly components; empty when there are none
	 */
	public record Fact(ValueId identity, String display, Type type, List<Fact> components, List<Fact> consumed) {
		public Fact {
			components = List.copyOf(components);
			consumed = List.copyOf(consumed);
		}
	}

	/**
	 * Derived input and output selection for one authored operation occurrence. Graph
	 * edges determine what executes next. Bindings determine which exact values an
	 * execution consumes and produces.
	 * <p>
	 * For example, suppose {@code normalize: Text -> Text} is followed by
	 * {@code decorate: Text -> Text} and {@code receipt: Text -> Receipt}. The binding for
	 * decorate selects normalize's output {@link ValueId}; receipt selects decorate's
	 * output. The root and both outputs share Java type Text, but execution reads the
	 * selected producer's saved value rather than searching by type. Reusing the same
	 * supplied Step in two {@code then} calls creates two {@link Placement placements}
	 * and distinct output identities, regardless of shared Java object identity.
	 * <p>
	 * Phase is an analysis context. Ordinary sequential bindings use {@code root}. The
	 * compiler's loop analysis distinguishes first entry ({@code /first}) from carried
	 * state ({@code /carried}), which may select different input facts at the same
	 * placement. These templates are not physical attempts or runtime iterations;
	 * durable execution currently admits only sequential operations and terminals.
	 * @param placement authored occurrence whose input/output selection this describes
	 * @param phase analysis context distinguishing first-entry and carried-state facts
	 * where applicable, otherwise root
	 * @param operation operation name from the definition's declaration, not a canonical
	 * StepRegistry name; registration is resolved separately against supplied objects
	 * @param input exact selected whole value or ordered assembled input fact
	 * @param output new output fact for this occurrence, with input consumption lineage
	 * @param expression descriptive rendering of the selected input or record assembly;
	 * never code parsed or evaluated by the runtime
	 */
	public record Binding(Placement placement, String phase, String operation, Fact input, Fact output,
			String expression) {
	}

	/**
	 * A value available after exclusive paths converge, with its proven alternatives.
	 * This is derived from path-sensitive facts; authors do not supply a context key or
	 * runtime type-search instruction. Reflection supplies type declarations, not the
	 * decision about which producer reaches this point.
	 */
	public record Capture(Placement placement, Fact result, List<Fact> alternatives) {
		public Capture {
			alternatives = List.copyOf(alternatives);
		}
	}

	/** Ordered results of parallel members, kept distinct from exclusive alternatives. */
	public record Product(Placement placement, Fact result, List<Fact> members) {
		public Product {
			members = List.copyOf(members);
		}
	}

	/** Analyzed type/configuration data checked against the emitted inspection graph. */
	public record Metadata(String kind, Type input, Type output, Map<String, String> configuration) {
		public Metadata {
			configuration = Map.copyOf(configuration);
		}
	}

	public record LoopContract(Placement placement, int maximum, LimitPolicy policy, Type output) {
		public LoopExit evaluate(boolean test, int completedIterations) {
			if (completedIterations < 1 || completedIterations > maximum)
				throw new IllegalArgumentException("invalid iteration coordinate");
			if (test)
				return LoopExit.TEST_TRUE;
			if (completedIterations < maximum)
				return LoopExit.CONTINUE;
			return policy == LimitPolicy.EXIT ? LoopExit.CAP_REACHED : LoopExit.LIMIT_FAILURE;
		}
	}

	/**
	 * Owned definition, checked graph and immutable analysis results produced together by
	 * {@link StructuredWorkflowCompiler}. This construction result supports inspection
	 * and verification; it has no selected application objects or durable progress.
	 * Runtime admission requires the additional capability and executable-selection
	 * checks in {@link ValidatedWorkflow}. Its graph contains refusing adapters rather
	 * than steps that an application can execute directly.
	 */
	public static final class Compilation<I, O> {

		private final Definition<I, O> definition;

		private final WorkflowGraph<I, O> graph;

		private final Map<Placement, Metadata> metadata;

		private final List<Binding> bindings;

		private final List<Capture> captures;

		private final List<Product> products;

		private final List<LoopContract> loops;

		private final Map<SummaryKey, RegionSummary> summaries;

		private final Map<SummaryKey, Metadata> phaseMetadata;

		Compilation(Definition<I, O> definition, WorkflowGraph<I, O> graph, Map<Placement, Metadata> metadata,
				List<Binding> bindings, List<Capture> captures, List<Product> products, List<LoopContract> loops,
				Map<SummaryKey, RegionSummary> summaries, Map<SummaryKey, Metadata> phaseMetadata) {
			this.definition = definition;
			this.graph = graph;
			this.metadata = Map.copyOf(metadata);
			this.bindings = List.copyOf(bindings);
			this.captures = List.copyOf(captures);
			this.products = List.copyOf(products);
			this.loops = List.copyOf(loops);
			this.summaries = Map.copyOf(summaries);
			this.phaseMetadata = Map.copyOf(phaseMetadata);
		}

		public Map<SummaryKey, Metadata> phaseMetadata() {
			return phaseMetadata;
		}

		public Map<SummaryKey, RegionSummary> summaries() {
			return summaries;
		}

		public Definition<I, O> definition() {
			return definition;
		}

		public WorkflowGraph<I, O> graph() {
			return graph;
		}

		public Map<Placement, Metadata> metadata() {
			return metadata;
		}

		public List<Binding> bindings() {
			return bindings;
		}

		public List<Capture> captures() {
			return captures;
		}

		public List<Product> products() {
			return products;
		}

		public List<LoopContract> loops() {
			return loops;
		}

		public Type input() {
			return definition.input();
		}

		public Type output() {
			return definition.output();
		}

	}

	public record ListType(Type element) implements ParameterizedType {
		@Override
		public Type[] getActualTypeArguments() {
			return new Type[] { element };
		}

		@Override
		public Type getRawType() {
			return List.class;
		}

		@Override
		public Type getOwnerType() {
			return null;
		}

		@Override
		public String getTypeName() {
			return "java.util.List<" + element.getTypeName() + ">";
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ParameterizedType p && p.getRawType() == List.class
					&& Arrays.equals(getActualTypeArguments(), p.getActualTypeArguments());
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(getActualTypeArguments()) ^ List.class.hashCode();
		}
	}

	public record ProductType(List<Type> roles) implements Type {
		public ProductType {
			roles = List.copyOf(roles);
		}

		@Override
		public String getTypeName() {
			return "product" + roles;
		}
	}

}
