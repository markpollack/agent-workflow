package io.github.markpollack.workflow.flows.compiler;

import java.util.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.workflow.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GraphStorageTest {

	static final class Echo implements Step<String, String> {

		public String execute(StepContext context, String input) {
			return input;
		}

	}

	@Test
	void childFreeCompilationUsesTheSameSequentialAdmissionRules() {
		for (Terminal terminal : Terminal.values()) {
			var source = new Definition<>("empty", String.class, String.class,
					List.<Node>of(new End(terminal, "reason")), null);
			assertThatThrownBy(
					() -> ValidatedWorkflow.compileWithChildren(source, Map.of(), Map.of(), DeadlinePolicy.DEFAULT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least one Step");
		}
		var step = new Echo();
		var fluent = Workflows.define("nonempty").then(step).terminate(Terminal.SUCCEEDED).build();
		var owned = fluent.definition();
		var source = new Definition<>(owned.name(), owned.input(), owned.output(), owned.nodes(), null);
		assertThat(
				ValidatedWorkflow.compileWithChildren(source, fluent.suppliedSteps(), Map.of(), DeadlinePolicy.DEFAULT)
					.authoredIdentity())
			.isEqualTo(fluent.authoredIdentity());
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void validationFingerprintAndTerminalProducerIgnoreStorageOrder() throws Exception {
		var step = new Echo();
		var fluent = Workflows.define("same")
			.then("first", step)
			.then("second", step)
			.terminate(Terminal.SUCCEEDED)
			.build();
		var definition = fluent.definition();
		var programmatic = ValidatedWorkflow.compile(
				new Definition<>(definition.name(), definition.input(), definition.output(), definition.nodes(), null),
				fluent.suppliedSteps());
		assertThat(programmatic.authoredIdentity()).isEqualTo(fluent.authoredIdentity());
		var compiled = StructuredWorkflowCompiler.compile(definition);
		var nodes = new ArrayList<>(compiled.graph().nodes());
		Collections.reverse(nodes);
		var edges = new ArrayList<>(compiled.graph().edges());
		Collections.reverse(edges);
		var bindings = new ArrayList<>(compiled.bindings());
		Collections.reverse(bindings);
		var graph = new WorkflowGraph<>(compiled.graph().name(), nodes, edges, compiled.graph().startNode(),
				compiled.graph().finishNode(), bindings);
		GraphVerification.verify(graph, compiled.metadata(), compiled.summaries(), bindings, compiled.captures(),
				compiled.products(), compiled.phaseMetadata(), Set.of(), definition);
		var changed = new Compilation(compiled.definition(), graph, compiled.metadata(), bindings, compiled.captures(),
				compiled.products(), compiled.loops(), compiled.summaries(), compiled.phaseMetadata());
		// Counterexample for internal storage: the production admission constructor
		// remains private.
		var constructor = ValidatedWorkflow.class.getDeclaredConstructor(Compilation.class, Map.class, Map.class,
				DeadlinePolicy.class, java.time.Duration.class);
		constructor.setAccessible(true);
		var reordered = (ValidatedWorkflow) constructor.newInstance(changed, fluent.suppliedSteps(), Map.of(),
				fluent.deadlinePolicy(), null);
		assertThat(reordered.authoredIdentity()).isEqualTo(fluent.authoredIdentity());
		assertThat(reordered.terminal()).isEqualTo(fluent.terminal());
	}

	@Test
	void missingExtraEdgesAndFalseSemanticNodesRefuseCheckedLowering() {
		var workflow = Workflows.define("edges")
			.then(new Echo())
			.then(new Echo())
			.terminate(Terminal.SUCCEEDED)
			.build();
		var compiled = StructuredWorkflowCompiler.compile(workflow.definition());
		var source = compiled.graph();
		for (boolean extra : List.of(false, true)) {
			var edges = new ArrayList<>(source.edges());
			if (extra)
				edges.add(WorkflowEdge.sequence(source.startNode(), source.finishNode()));
			else
				edges.removeFirst();
			var graph = new WorkflowGraph<>(source.name(), source.nodes(), edges, source.startNode(),
					source.finishNode(), source.bindings());
			assertThatThrownBy(() -> verify(compiled, graph)).isInstanceOf(IllegalArgumentException.class);
		}
		var nodes = new ArrayList<>(source.nodes());
		for (int i = 0; i < nodes.size(); i++)
			if (nodes.get(i) instanceof WorkflowNode.TerminalNode end)
				nodes.set(i, new WorkflowNode.TerminalNode(end.name(), Terminal.FAILED, "different", null));
		var graph = new WorkflowGraph<>(source.name(), nodes, source.edges(), source.startNode(), source.finishNode(),
				source.bindings());
		assertThatThrownBy(() -> verify(compiled, graph)).hasMessageContaining("terminal node disagreement");
		var withoutBindings = new WorkflowGraph<>(source.name(), source.nodes(), source.edges(), source.startNode(),
				source.finishNode(), List.of());
		assertThatThrownBy(() -> verify(compiled, withoutBindings)).hasMessageContaining("graph binding disagreement");
	}

	private static void verify(Compilation<?, ?> compiled, WorkflowGraph<?, ?> graph) {
		GraphVerification.verify(graph, compiled.metadata(), compiled.summaries(), compiled.bindings(),
				compiled.captures(), compiled.products(), compiled.phaseMetadata(), Set.of(), compiled.definition());
	}

	@Test
	void identityFramingUsesUtf16LengthsAndUtf8DigestWithoutNormalization() {
		var framed = new StringBuilder();
		for (String field : List.of("A", "😀", "é", "é"))
			IdentityEncoding.field(framed, field);
		assertThat(framed.toString()).isEqualTo("1:A2:😀1:é2:é");
		assertThat(IdentityEncoding.digest(framed.toString()))
			.isEqualTo("sha256:9d3d0e08e894ca5540367c131dba7749cfb35d19146c87da47ee6d78fc31f83e");
	}

}
