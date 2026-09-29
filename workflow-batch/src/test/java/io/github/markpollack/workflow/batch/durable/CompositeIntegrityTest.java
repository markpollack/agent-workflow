package io.github.markpollack.workflow.batch.durable;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static io.github.markpollack.workflow.batch.durable.CompositeExecutionTest.*;

class CompositeIntegrityTest {

	@TempDir
	Path directory;

	@Test
	void malformedScopeDefinitionProgressOutcomeAndReceiptRefuseBeforeEffectsOrWrites() throws Exception {
		for (String mutation : List.of("definition", "parent", "input", "return", "opening", "scope-id", "counter",
				"bounds", "extra-descriptor", "missing-descriptor", "open-outcome", "missing-outcome",
				"returned-no-receipt", "early-parent", "duplicate-frontier", "stranded", "missing-child",
				"entered-no-call", "waiting-no-call", "missing-terminal", "receipt-output", "receipt-outcome",
				"missing-receipt", "foreign-value")) {
			var calls = new ArrayList<String>();
			var step = new Prefix("F", calls);
			var workflow = wrapper(poll(step));
			var file = directory.resolve(mutation);
			try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("fast", step)), compatibility())) {
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				if (!mutation.equals("duplicate-frontier"))
					runtime.advance(id, workflow);
				boolean completed = Set
					.of("open-outcome", "missing-outcome", "returned-no-receipt", "missing-terminal", "receipt-output",
							"receipt-outcome", "missing-receipt")
					.contains(mutation);
				if (completed)
					runtime.advance(id, workflow);
				if (mutation.startsWith("receipt-") || mutation.equals("missing-receipt"))
					runtime.advance(id, workflow);
				var before = runtime.inspect(id);
				int effects = calls.size();
				var child = before.scopes().stream().filter(s -> !s.parentScope().isEmpty()).findFirst().orElseThrow();
				mutate(file, id, tree -> {
					var run = (ObjectNode) tree;
					var scopes = (ObjectNode) tree.path("scopes");
					var c = (ObjectNode) scopes.path(child.id());
					var parent = (ObjectNode) scopes.path(before.rootScope());
					var nodes = (ObjectNode) c.path("nodes");
					switch (mutation) {
						case "entered-no-call", "waiting-no-call" -> {
							var ready = java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
								.filter(n -> n.path("phase").asText().equals("READY"))
								.findFirst()
								.orElseThrow();
							((ObjectNode) ready).put("phase",
									mutation.equals("entered-no-call") ? "ENTERED" : "WAITING_CHILD");
						}
						case "definition" -> c.put("definition", before.rootDefinition());
						case "parent" -> c.put("parent", child.id());
						case "input" -> c.put("input", "foreign");
						case "return" -> c.put("returnNode", "foreign");
						case "opening" -> c.put("opening", "foreign");
						case "scope-id" -> c.put("id", "foreign");
						case "counter" -> run.put("logicalCount", 99);
						case "bounds" -> ((ObjectNode) run.path("bounds")).put("logical", 99);
						case "extra-descriptor" -> ((ObjectNode) run.path("definitions")).set("foreign",
								run.path("definitions").elements().next().deepCopy());
						case "missing-descriptor" -> ((ObjectNode) run.path("definitions")).remove(child.definition());
						case "open-outcome" -> c.put("lifecycle", "OPEN");
						case "missing-outcome" -> c.remove("localOutcome");
						case "returned-no-receipt" -> c.put("lifecycle", "RETURNED");
						case "early-parent" ->
							((ObjectNode) parent.path("nodes").elements().next()).put("phase", "READY");
						case "duplicate-frontier" -> {
							var leaves = run.path("definitions").path(child.definition()).path("leaves");
							String extra = java.util.stream.StreamSupport
								.stream(java.util.Spliterators.spliteratorUnknownSize(leaves.fieldNames(), 0), false)
								.filter(k -> !nodes.has(k))
								.findFirst()
								.orElseThrow();
							var n = nodes.objectNode();
							n.put("node", extra);
							n.put("phase", "READY");
							n.put("invocation", "");
							n.put("settlement", "");
							nodes.set(extra, n);
						}
						case "stranded" -> {
							var names = new ArrayList<String>();
							nodes.fieldNames().forEachRemaining(names::add);
							for (String name : names)
								if (nodes.path(name).path("phase").asText().equals("READY"))
									nodes.remove(name);
						}
						case "missing-child" -> scopes.remove(child.id());
						case "missing-terminal" -> nodes.remove(child.localOutcome().source());
						case "receipt-output" ->
							((ObjectNode) run.path("returns").elements().next()).put("output", "foreign");
						case "receipt-outcome" ->
							((ObjectNode) run.path("returns").elements().next()).put("outcome", "foreign");
						case "missing-receipt" -> ((ObjectNode) run.path("returns")).removeAll();
						case "foreign-value" ->
							((ObjectNode) run.path("values").elements().next()).put("scope", child.id());
					}
				});
				String corrupt = state(file, id);
				for (boolean direct : List.of(true, false))
					assertThatThrownBy(() -> {
						if (direct)
							runtime.advance(id, workflow);
						else
							runtime.resume(id, workflow);
					}).as(mutation)
						.isInstanceOfSatisfying(WorkflowRefusal.class, e -> assertThat(e.code())
							.isEqualTo(mutation.equals("stranded") ? "STRANDED_PROGRESS" : "PROGRESS_INVALID"));
				assertThat(state(file, id)).isEqualTo(corrupt);
				assertThat(calls).hasSize(effects);
			}
		}
	}

	@Test
	void unresolvedInnerAttemptReusesInputAndLogicalChargeAndExhaustsLocally() {
		var calls = new ArrayList<String>();
		var step = new Prefix("F", calls);
		var workflow = wrapper(poll(step));
		var registry = StepRegistry.of(Map.of("fast", step));
		String id;
		RunSnapshot saved;
		BoundaryHooks crash = (point, run) -> {
			if (point.equals("AFTER_DISPATCH_COMMIT"))
				throw new DurableRaceTest.SimulatedCrash();
		};
		try (var runtime = new DurableWorkflows(directory.resolve("runs"), registry, compatibility(),
				new ExecutionPolicy(1), crash)) {
			id = runtime.start(workflow, "one", new Text("x")).runId();
			runtime.advance(id, workflow);
			assertThatThrownBy(() -> runtime.advance(id, workflow)).isInstanceOf(DurableRaceTest.SimulatedCrash.class);
			saved = runtime.inspect(id);
		}
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), registry, compatibility(),
				new ExecutionPolicy(1))) {
			var end = runtime.resume(id, workflow);
			assertThat(end.status()).isEqualTo(RunSnapshot.Status.FAILED);
			assertThat(end.reason().code()).isEqualTo("ATTEMPTS_EXHAUSTED");
			assertThat(calls).isEmpty();
			assertThat(end.logicalInvocations()).isEqualTo(saved.logicalInvocations());
			assertThat(end.invocations().getLast().inputValue()).isEqualTo(saved.invocations().getLast().inputValue());
			assertThat(end.invocations().getLast().attempts()).hasSize(1);
			assertThat(end.returns()).hasSize(1);
		}
	}

	@Test
	void explicitLocalNonSuccessIsPropagatedAndNeverBecomesGlobalCancellation() {
		for (Terminal terminal : List.of(Terminal.FAILED, Terminal.CANCELLED)) {
			var calls = new ArrayList<String>();
			var step = new Prefix("F", calls);
			var child = Workflows.define("child").then(step).terminate(terminal, "business reason").build();
			// A statically non-returning child is itself the parent's final region; an
			// additional normal successor would correctly be unreachable authored work.
			var definition = new Definition<>("parent", Text.class, Text.class, List.of(new Child("call", child)),
					null);
			var workflow = ValidatedWorkflow.compile(definition, Map.of());
			try (var runtime = DurableWorkflows.open(directory.resolve(terminal.name()),
					StepRegistry.of(Map.of("fast", step)), compatibility())) {
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				var end = runtime.resume(id, workflow);
				assertThat(end.status()).isEqualTo(RunSnapshot.Status.FAILED);
				assertThat(end.reason().code()).isEqualTo("AUTHORED_" + terminal);
				assertThat(end.scopes()
					.stream()
					.filter(s -> !s.parentScope().isEmpty())
					.findFirst()
					.orElseThrow()
					.localOutcome()
					.status()).isEqualTo(terminal.name());
				assertThat(end.returns()).hasSize(1);
				assertThat(calls).containsExactly("F:x");
			}
		}
	}

	@Test
	void localDeadlineEqualityAndNonfinalProgressExpireWithoutInventingCompletion() throws Exception {
		for (long offset : List.of(99L, 100L, 101L)) {
			var calls = new ArrayList<String>();
			var step = new Prefix("F", calls);
			var child = Workflows.define("short")
				.maxDuration(Duration.ofMillis(100))
				.then(step)
				.terminate(Terminal.SUCCEEDED)
				.build();
			var workflow = wrapper(child);
			var file = directory.resolve("offset" + offset);
			BoundaryHooks schedule = (point, id) -> {
				if (point.equals("AFTER_HANDLER_RETURN"))
					time(10_000 + offset);
			};
			try (var runtime = new DurableWorkflows(file, StepRegistry.of(Map.of("fast", step)), compatibility(),
					ExecutionPolicy.DEFAULT, schedule)) {
				controlledClock(file, 10_000);
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				var end = runtime.resume(id, workflow);
				assertThat(end.status())
					.isEqualTo(offset < 100 ? RunSnapshot.Status.SUCCEEDED : RunSnapshot.Status.FAILED);
				assertThat(end.values()).hasSize(offset < 100 ? 4 : 2);
			}
		}
		var calls = new ArrayList<String>();
		var step = new Prefix("F", calls);
		var child = Workflows.define("short")
			.maxDuration(Duration.ofMillis(100))
			.then(step)
			.then(step)
			.terminate(Terminal.SUCCEEDED)
			.build();
		var workflow = wrapper(child);
		var file = directory.resolve("nonfinal");
		try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("fast", step)), compatibility())) {
			controlledClock(file, 10_000);
			String id = runtime.start(workflow, "one", new Text("x")).runId();
			runtime.advance(id, workflow);
			var accepted = runtime.advance(id, workflow);
			time(10_100);
			var end = runtime.resume(id, workflow);
			assertThat(end.status()).isEqualTo(RunSnapshot.Status.FAILED);
			assertThat(end.values()).containsAll(accepted.values());
			assertThat(calls).containsExactly("F:x");
		}
	}

}
