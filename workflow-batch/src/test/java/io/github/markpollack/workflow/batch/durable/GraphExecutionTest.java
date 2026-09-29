package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.util.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class GraphExecutionTest {

	@TempDir
	Path directory;

	public record Text(String value) {
	}

	static final class Prefix implements Step<Text, Text> {

		final String prefix;

		final List<String> entered;

		Prefix(String prefix, List<String> entered) {
			this.prefix = prefix;
			this.entered = entered;
		}

		public Text execute(StepContext context, Text input) {
			entered.add(prefix);
			return new Text(prefix + input.value());
		}

	}

	private static ExecutionCompatibility compatibility() {
		return new ExecutionCompatibility("graph", "v1", Map.of());
	}

	@Test
	void graphEdgesGovernExecutionAcrossReorderedStorageRegistryAndFreshObjects() throws Exception {
		var calls = new ArrayList<String>();
		var first = new Prefix("A", calls);
		var second = new Prefix("B", calls);
		var workflow = Workflows.define("order")
			.then("first", first)
			.then("second", second)
			.terminate(Terminal.SUCCEEDED)
			.build();
		String identity = workflow.authoredIdentity();
		reorder(workflow);
		String id;
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"),
				StepRegistry.of(Map.of("z", first, "a", second)), compatibility())) {
			var admitted = runtime.start(workflow, "one", new Text("x"));
			id = admitted.runId();
			assertThat(admitted.currentNode()).isEqualTo(workflow.graph().startNode());
			var saved = runtime.advance(id, workflow);
			assertThat(calls).containsExactly("A");
			assertThat(saved.currentNode())
				.isEqualTo(workflow.graph().unconditionalSuccessor(workflow.graph().startNode()));
		}
		var freshCalls = new ArrayList<String>();
		var freshFirst = new Prefix("A", freshCalls);
		var freshSecond = new Prefix("B", freshCalls);
		var fresh = Workflows.define("order")
			.then("first", freshFirst)
			.then("second", freshSecond)
			.terminate(Terminal.SUCCEEDED)
			.build();
		assertThat(fresh.authoredIdentity()).isEqualTo(identity);
		var registry = new LinkedHashMap<String, Step<?, ?>>();
		registry.put("a", freshSecond);
		registry.put("unused", new Prefix("unused", freshCalls));
		registry.put("z", freshFirst);
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), StepRegistry.of(registry),
				compatibility())) {
			assertThat(runtime.resume(id, fresh).currentNode()).isEqualTo(fresh.terminal().name());
			assertThat(runtime.result(id, fresh)).isEqualTo(new Text("BAx"));
			assertThat(freshCalls).containsExactly("B");
		}
	}

	// Deliberately change only internal storage for this counterexample; no public
	// raw-graph admission is added.
	private static void reorder(ValidatedWorkflow workflow) throws Exception {
		var graph = workflow.graph();
		var nodes = new ArrayList<>(graph.nodes());
		Collections.reverse(nodes);
		var edges = new ArrayList<>(graph.edges());
		Collections.reverse(edges);
		var bindings = new ArrayList<>(graph.bindings());
		Collections.reverse(bindings);
		var field = ValidatedWorkflow.class.getDeclaredField("graph");
		field.setAccessible(true);
		field.set(workflow,
				new WorkflowGraph<>(graph.name(), nodes, edges, graph.startNode(), graph.finishNode(), bindings));
	}

	@Test
	void graphEdgesAndBindingsAreCompatibilityFactsEvenWithUnchangedSelections() throws Exception {
		for (boolean changeEdge : List.of(true, false)) {
			var calls = new ArrayList<String>();
			var step = new Prefix("A", calls);
			var original = Workflows.define("changed").then(step).then(step).terminate(Terminal.SUCCEEDED).build();
			var changed = Workflows.define("changed").then(step).then(step).terminate(Terminal.SUCCEEDED).build();
			var graph = changed.graph();
			var edges = new ArrayList<>(graph.edges());
			var bindings = new ArrayList<>(graph.bindings());
			if (changeEdge) {
				edges.removeIf(edge -> edge.from().equals(graph.startNode()));
				edges.add(io.github.markpollack.workflow.flows.workflow.WorkflowEdge.sequence(graph.startNode(),
						changed.terminal().name()));
			}
			else {
				var second = bindings.get(1);
				bindings.set(1,
						new io.github.markpollack.workflow.flows.compiler.WorkflowModel.Binding(second.placement(),
								second.phase(), second.operation(), bindings.getFirst().input(), second.output(),
								second.expression()));
			}
			// Isolate identity coverage from selection and type changes. Public raw graph
			// admission is separately refused.
			var graphField = ValidatedWorkflow.class.getDeclaredField("graph");
			graphField.setAccessible(true);
			graphField.set(changed, new WorkflowGraph<>(graph.name(), graph.nodes(), edges, graph.startNode(),
					graph.finishNode(), bindings));
			var fingerprint = ValidatedWorkflow.class.getDeclaredMethod("authoredIdentity", TypeContracts.class);
			fingerprint.setAccessible(true);
			var identity = ValidatedWorkflow.class.getDeclaredField("authoredIdentity");
			identity.setAccessible(true);
			identity.set(changed, fingerprint.invoke(changed, new TypeContracts()));
			assertThat(changed.authoredIdentity()).isNotEqualTo(original.authoredIdentity());
			var registry = StepRegistry.of(Map.of("step", step));
			assertThat(new WorkflowExecutionBindings(registry, compatibility(), changed).selections())
				.isEqualTo(new WorkflowExecutionBindings(registry, compatibility(), original).selections());
			Path file = directory.resolve(changeEdge ? "edge" : "binding");
			try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
				String id = runtime.start(original, "one", new Text("x")).runId();
				String before = state(file, id);
				assertThatThrownBy(() -> runtime.resume(id, changed)).isInstanceOfSatisfying(WorkflowRefusal.class,
						failure -> assertThat(failure.code()).isEqualTo("COMPATIBILITY"));
				assertThat(state(file, id)).isEqualTo(before);
				assertThat(calls).isEmpty();
			}
		}
	}

	@Test
	void corruptCursorAndAllowanceRefuseBeforeMutationOrEntry() throws Exception {
		for (String corruption : List.of("skip", "back", "foreign", "absent", "allowance", "authored")) {
			var calls = new ArrayList<String>();
			var step = new Prefix("A", calls);
			var workflow = Workflows.define("cursor")
				.then("first", step)
				.then("second", step)
				.terminate(Terminal.SUCCEEDED)
				.build();
			Path file = directory.resolve(corruption);
			try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("step", step)), compatibility())) {
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				mutate(file, id, tree -> {
					var json = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
					switch (corruption) {
						case "skip" -> json.put("node", workflow.terminal().name());
						case "back" -> json.put("node", workflow.graph().startNode());
						case "foreign" -> json.put("node", "foreign");
						case "absent" -> json.remove("node");
						case "allowance" -> json.put("maximumAttempts", 4);
						case "authored" -> json.put("authored", "changed");
					}
				});
				String before = state(file, id);
				assertThatThrownBy(() -> runtime.resume(id, workflow)).isInstanceOf(WorkflowRefusal.class);
				assertThat(state(file, id)).isEqualTo(before);
				assertThat(calls).containsExactly("A");
			}
		}
	}

	@Test
	void explicitFailedAndCancelledTerminalsRetainReasonAndNeverFabricateAResult() throws Exception {
		for (Terminal terminal : List.of(Terminal.FAILED, Terminal.CANCELLED)) {
			var calls = new ArrayList<String>();
			var step = new Prefix("A", calls);
			var workflow = Workflows.define("terminal").then(step).terminate(terminal, "business reason").build();
			try (var runtime = DurableWorkflows.open(directory.resolve(terminal.name()),
					StepRegistry.of(Map.of("step", step)), compatibility())) {
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				var end = runtime.resume(id, workflow);
				assertThat(end.status().name()).isEqualTo(terminal.name());
				assertThat(end.currentNode()).isEqualTo(workflow.terminal().name());
				assertThat(end.reason().message()).isEqualTo("business reason");
				assertThat(end.reason().actor()).isNotBlank();
				assertThat(end.reason().time()).isNotNull();
				assertThatThrownBy(() -> runtime.result(id, workflow)).isInstanceOf(WorkflowRefusal.class);
				assertThat(runtime.resume(id, workflow)).isEqualTo(end);
				assertThat(calls).containsExactly("A");
			}
		}
	}

	@Test
	void oldOrMalformedSqlVersionIsRefusedBeforeWritableOpen() throws Exception {
		for (String type : List.of("VARCHAR", "DECIMAL", "INTEGER")) {
			Path file = directory.resolve(type);
			try (var runtime = DurableWorkflows.open(file)) {
				assertThat(runtime.discover()).isEmpty();
			}
			try (var connection = connect(file); var statement = connection.createStatement()) {
				statement.execute("ALTER TABLE aw_store_format ALTER COLUMN version " + type);
				statement.execute("UPDATE aw_store_format SET version=" + (type.equals("VARCHAR") ? "'5'" : type.equals("INTEGER") ? "4" : "5.4"));
			}
			byte[] before = Files.readAllBytes(Path.of(file + ".mv.db"));
			assertThatThrownBy(() -> DurableWorkflows.open(file)).isInstanceOfSatisfying(WorkflowRefusal.class,
					failure -> assertThat(failure.code()).isEqualTo("STORE_FORMAT"));
			assertThat(Files.readAllBytes(Path.of(file + ".mv.db"))).isEqualTo(before);
		}
	}

}
