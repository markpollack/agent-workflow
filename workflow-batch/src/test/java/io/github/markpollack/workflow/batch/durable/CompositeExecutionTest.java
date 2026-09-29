package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class CompositeExecutionTest {

	@TempDir
	Path directory;

	public record Text(String value) {
	}

	static final class Prefix implements Step<Text, Text> {

		final String prefix;

		final List<String> calls;

		Prefix(String prefix, List<String> calls) {
			this.prefix = prefix;
			this.calls = calls;
		}

		public Text execute(StepContext context, Text input) {
			calls.add(prefix + ":" + input.value());
			return new Text(prefix + input.value());
		}

	}

	static ExecutionCompatibility compatibility() {
		return new ExecutionCompatibility("composites", "v1", Map.of("fast", "F", "careful", "C"));
	}

	static ValidatedWorkflow poll(Prefix step) {
		return Workflows.define("poll").then("fetch", step).then("assess", step).terminate(Terminal.SUCCEEDED).build();
	}

	static ValidatedWorkflow wrapper(ValidatedWorkflow child) {
		return Workflows.define("wrapper").subWorkflow("poll", child).terminate(Terminal.SUCCEEDED).build();
	}

	static ValidatedWorkflow root(ValidatedWorkflow before, Prefix middle, ValidatedWorkflow after) {
		return Workflows.define("root")
			.subWorkflow("before", before)
			.then("middle", middle)
			.subWorkflow("after", after)
			.terminate(Terminal.SUCCEEDED)
			.build();
	}

	@Test
	void repeatedAndNestedDefinitionsUseScopedExactValuesAndReturnOnce() {
		var calls = new ArrayList<String>();
		var step = new Prefix("F", calls);
		var middle = new Prefix("M", calls);
		var poll = poll(step);
		var wrapped = wrapper(poll);
		var workflow = root(wrapped, middle, wrapped);
		var registry = StepRegistry.of(Map.of("fast", step, "middle", middle));
		var prepared = new WorkflowExecutionBindings(registry, compatibility(), workflow);
		assertThat(prepared.descriptors()).hasSize(3);
		assertThat(prepared.bounds()).isEqualTo(new CompositionBounds(5, 4, 2, 9, 5, 15));
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), registry, compatibility())) {
			String id = runtime.start(workflow, "one", new Text("x")).runId();
			var end = runtime.resume(id, workflow);
			assertThat(end.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(id, workflow)).isEqualTo(new Text("FFMFFx"));
			assertThat(calls).containsExactly("F:x", "F:Fx", "M:FFx", "F:MFFx", "F:FMFFx");
			assertThat(end.scopes()).hasSize(5);
			assertThat(end.invocations()).hasSize(9);
			assertThat(end.returns()).hasSize(4);
			assertThat(end.invocations().stream().map(RunSnapshot.Invocation::invocationId).distinct()).hasSize(9);
			assertThat(end.scopes().stream().map(RunSnapshot.Scope::id).distinct()).hasSize(5);
			assertThat(end.logicalInvocations()).isEqualTo(9);
			assertThat(runtime.resume(id, workflow)).isEqualTo(end);
			assertThat(calls).hasSize(5);
			assertThat(end.scopes().stream().filter(s -> s.lifecycle().equals("RETURNED"))).hasSize(4);
		}
	}

	@Test
	void sameAuthoredDifferentBeansRemainQualifiedThroughFreshNestedDefinitions() {
		var calls = new ArrayList<String>();
		var fast = new Prefix("F", calls);
		var careful = new Prefix("C", calls);
		var middle = new Prefix("M", calls);
		var f = poll(fast);
		var c = poll(careful);
		assertThat(f.authoredIdentity()).isEqualTo(c.authoredIdentity());
		var workflow = root(wrapper(f), middle, wrapper(c));
		var registry = StepRegistry.of(Map.of("fast", fast, "careful", careful, "middle", middle));
		var prepared = new WorkflowExecutionBindings(registry, compatibility(), workflow);
		assertThat(prepared.descriptors()).hasSize(5);
		String id;
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), registry, compatibility())) {
			id = runtime.start(workflow, "one", new Text("x")).runId();
			runtime.advance(id, workflow);
			runtime.advance(id, workflow);
			runtime.advance(id, workflow);
			assertThat(calls).containsExactly("F:x");
		}
		calls.clear();
		var freshFast = new Prefix("F", calls);
		var freshCareful = new Prefix("C", calls);
		var freshMiddle = new Prefix("M", calls);
		var fresh = root(wrapper(poll(freshFast)), freshMiddle, wrapper(poll(freshCareful)));
		var freshRegistry = StepRegistry.of(Map.of("middle", freshMiddle, "careful", freshCareful, "fast", freshFast,
				"unused", new Prefix("unused", calls)));
		assertThat(new WorkflowExecutionBindings(freshRegistry, compatibility(), fresh).descriptors())
			.isEqualTo(prepared.descriptors());
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), freshRegistry, compatibility())) {
			var swapped = root(wrapper(poll(freshCareful)), freshMiddle, wrapper(poll(freshFast)));
			assertThat(swapped.authoredIdentity()).isEqualTo(fresh.authoredIdentity());
			assertThatThrownBy(() -> runtime.advance(id, swapped)).isInstanceOfSatisfying(WorkflowRefusal.class,
					e -> assertThat(e.code()).isEqualTo("COMPATIBILITY"));
			assertThat(calls).isEmpty();
			runtime.resume(id, fresh);
			assertThat(runtime.result(id, fresh)).isEqualTo(new Text("CCMFFx"));
			assertThat(calls).containsExactly("F:Fx", "M:FFx", "C:MFFx", "C:CMFFx");
		}
	}

	@Test
	void memoizedDagCountsEveryCallAndRefusesDepthWorkAndOverflowBeforeAdmission() {
		var step = new Prefix("F", new ArrayList<>());
		var registry = StepRegistry.of(Map.of("fast", step));
		var leaf = poll(step);
		var nested = wrapper(wrapper(leaf));
		assertThat(new WorkflowExecutionBindings(registry, compatibility(), nested, new ExecutionPolicy(3, 2, 100))
			.bounds()
			.depth()).isEqualTo(2);
		assertThatThrownBy(
				() -> new WorkflowExecutionBindings(registry, compatibility(), nested, new ExecutionPolicy(3, 1, 100)))
			.isInstanceOfSatisfying(WorkflowRefusal.class, e -> assertThat(e.code()).isEqualTo("COMPOSITION_LIMIT"));
		var dag = leaf;
		for (int i = 0; i < 32; i++)
			dag = Workflows.define("level-" + i)
				.subWorkflow("one", dag)
				.subWorkflow("two", dag)
				.terminate(Terminal.SUCCEEDED)
				.build();
		var wide = dag;
		assertThatThrownBy(() -> new WorkflowExecutionBindings(registry, compatibility(), wide))
			.isInstanceOfSatisfying(WorkflowRefusal.class, e -> assertThat(e.code()).isEqualTo("COMPOSITION_LIMIT"));
		for (int i = 32; i < 64; i++)
			dag = Workflows.define("level-" + i)
				.subWorkflow("one", dag)
				.subWorkflow("two", dag)
				.terminate(Terminal.SUCCEEDED)
				.build();
		var overflowing = dag;
		assertThatThrownBy(() -> new WorkflowExecutionBindings(registry, compatibility(), overflowing,
				new ExecutionPolicy(3, 100, Long.MAX_VALUE)))
			.isInstanceOfSatisfying(WorkflowRefusal.class, e -> assertThat(e.code()).isEqualTo("COMPOSITION_LIMIT"));
	}

	@Test
	void finalLocalOutcomeSurvivesOwnDeadlineButAncestorMayRevokeReturn() throws Exception {
		for (String disposition : List.of("return", "cancel", "expire")) {
			var calls = new ArrayList<String>();
			var step = new Prefix("F", calls);
			var child = Workflows.define("short")
				.maxDuration(Duration.ofMillis(100))
				.then(step)
				.terminate(Terminal.SUCCEEDED)
				.build();
			var workflow = wrapper(child);
			var file = directory.resolve(disposition);
			try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("fast", step)), compatibility())) {
				long now = System.currentTimeMillis() + 1000;
				controlledClock(file, now);
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				time(now + 99);
				var committed = runtime.advance(id, workflow);
				var local = committed.scopes()
					.stream()
					.filter(s -> !s.parentScope().isEmpty())
					.findFirst()
					.orElseThrow();
				assertThat(local.lifecycle()).isEqualTo("LOCAL_TERMINAL");
				assertThat(local.localOutcome().status()).isEqualTo("SUCCEEDED");
				time(now + 101);
				if (disposition.equals("cancel"))
					runtime.cancel(id, "owner", "stop");
				if (disposition.equals("expire"))
					time(committed.deadline().toEpochMilli());
				var end = runtime.resume(id, workflow);
				var retained = end.scopes().stream().filter(s -> s.id().equals(local.id())).findFirst().orElseThrow();
				assertThat(retained.localOutcome()).isEqualTo(local.localOutcome());
				assertThat(retained.lifecycle()).isEqualTo(disposition.equals("return") ? "RETURNED" : "REVOKED");
				assertThat(end.returns()).hasSize(disposition.equals("return") ? 1 : 0);
				assertThat(calls).containsExactly("F:x");
			}
		}
	}

	@Test
	void defaultDepthBoundaryAndStoredOrderDoNotChangeSelection() throws Exception {
		var step = new Prefix("F", new ArrayList<>());
		var registry = StepRegistry.of(Map.of("fast", step));
		var nested = poll(step);
		for (int i = 0; i < 32; i++)
			nested = wrapper(nested);
		var atLimit = new WorkflowExecutionBindings(registry, compatibility(), nested);
		assertThat(atLimit.bounds().depth()).isEqualTo(32);
		assertThat(atLimit.bounds().logical()).isEqualTo(34);
		var tooDeep = wrapper(nested);
		assertThatThrownBy(() -> new WorkflowExecutionBindings(registry, compatibility(), tooDeep))
			.hasMessageContaining("depth");
		var body = poll(step);
		var root = wrapper(body);
		var before = new WorkflowExecutionBindings(registry, compatibility(), root);
		for (var workflow : List.of(body, root)) {
			var graph = workflow.graph();
			var nodes = new ArrayList<>(graph.nodes());
			Collections.reverse(nodes);
			var edges = new ArrayList<>(graph.edges());
			Collections.reverse(edges);
			var bindings = new ArrayList<>(graph.bindings());
			Collections.reverse(bindings);
			var field = ValidatedWorkflow.class.getDeclaredField("graph");
			field.setAccessible(true);
			field.set(workflow, new io.github.markpollack.workflow.flows.workflow.WorkflowGraph<>(graph.name(), nodes,
					edges, graph.startNode(), graph.finishNode(), bindings));
		}
		assertThat(new WorkflowExecutionBindings(registry, compatibility(), root).descriptors())
			.isEqualTo(before.descriptors());
		try (var runtime = DurableWorkflows.open(directory.resolve("reordered"), registry, compatibility())) {
			String id = runtime.start(root, "one", new Text("x")).runId();
			runtime.resume(id, root);
			assertThat(runtime.result(id, root)).isEqualTo(new Text("FFx"));
		}
	}

}
