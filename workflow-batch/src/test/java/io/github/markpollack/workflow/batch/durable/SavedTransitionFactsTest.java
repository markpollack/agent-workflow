package io.github.markpollack.workflow.batch.durable;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.CompositeExecutionTest.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class SavedTransitionFactsTest {

	@TempDir
	Path directory;

	@Test
	void childCannotClaimSelfAsRevokingAncestor() throws Exception {
		var effects = new ArrayList<String>();
		var step = new Prefix("F", effects);
		var child = Workflows.define("child").then(step).terminate(Terminal.SUCCEEDED).build();
		var w = wrapper(child);
		var file = directory.resolve("revocation");
		try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("step", step)), compatibility())) {
			String id = runtime.start(w, "key", new Text("x")).runId();
			runtime.advance(id, w);
			runtime.advance(id, w);
			var cancelled = runtime.cancel(id, "operator", "stop");
			String childId = cancelled.scopes()
				.stream()
				.filter(x -> !x.parentScope().isEmpty())
				.findFirst()
				.orElseThrow()
				.id();
			mutate(file, id,
					t -> ((ObjectNode) t.path("scopes").path(childId).path("revocation")).put("ancestor", childId));
			assertRefusesUnchanged(runtime, file, id, w, effects);
		}
	}

	@Test
	void runtimeClosureCannotInventSuccessWithoutAuthoredTerminal() throws Exception {
		var effects = new ArrayList<String>();
		var step = new Prefix("F", effects);
		var w = Workflows.define("one").then(step).terminate(Terminal.SUCCEEDED).build();
		var file = directory.resolve("fake-success");
		try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("step", step)), compatibility())) {
			String id = runtime.start(w, "key", new Text("x")).runId();
			var cancelled = runtime.cancel(id, "operator", "stop");
			mutate(file, id, t -> {
				var r = (ObjectNode) t;
				var s = (ObjectNode) t.path("scopes").path(cancelled.rootScope());
				var o = (ObjectNode) s.path("localOutcome");
				r.put("status", "SUCCEEDED");
				r.put("output", s.path("input").asText());
				o.put("status", "SUCCEEDED");
				o.put("successValue", s.path("input").asText());
			});
			try (var c = connect(file);
					var statement = c.prepareStatement("UPDATE aw_run SET status='SUCCEEDED' WHERE id=?")) {
				statement.setString(1, id);
				statement.executeUpdate();
			}
			assertRefusesUnchanged(runtime, file, id, w, effects);
			assertThat(effects).isEmpty();
		}
	}

	@Test
	void revokedAttemptMustMatchGoverningDisposition() throws Exception {
		var effects = new ArrayList<String>();
		var step = new Prefix("F", effects);
		var w = Workflows.define("one").then(step).terminate(Terminal.SUCCEEDED).build();
		var file = directory.resolve("attempt-disposition");
		BoundaryHooks crash = (point, id) -> {
			if (point.equals("AFTER_DISPATCH_COMMIT"))
				throw new DurableRaceTest.SimulatedCrash();
		};
		try (var runtime = new DurableWorkflows(file, StepRegistry.of(Map.of("step", step)), compatibility(),
				ExecutionPolicy.DEFAULT, crash)) {
			String id = runtime.start(w, "key", new Text("x")).runId();
			assertThatThrownBy(() -> runtime.advance(id, w)).isInstanceOf(DurableRaceTest.SimulatedCrash.class);
			runtime.cancel(id, "operator", "stop");
			mutate(file, id, t -> ((ObjectNode) t.path("invocations").get(0).path("attempts").get(0)).put("disposition",
					"COMMITTED"));
			assertRefusesUnchanged(runtime, file, id, w, effects);
		}
	}

	@Test
	void attemptCannotBeChargedAfterItsAcceptedResult() throws Exception {
		var effects = new ArrayList<String>();
		var step = new Prefix("F", effects);
		var w = Workflows.define("one").then(step).terminate(Terminal.SUCCEEDED).build();
		var file = directory.resolve("attempt-time");
		try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("step", step)), compatibility())) {
			String id = runtime.start(w, "key", new Text("x")).runId();
			var end = runtime.resume(id, w);
			mutate(file, id, t -> ((ObjectNode) t.path("invocations").get(0).path("attempts").get(0)).put("charged",
					end.deadline().toEpochMilli() + 1));
			assertRefusesUnchanged(runtime, file, id, w, effects);
		}
	}

	@Test
	void returnCannotBeAcceptedAfterGoverningParentDeadline() throws Exception {
		var effects = new ArrayList<String>();
		var step = new Prefix("F", effects);
		var child = Workflows.define("child").then(step).terminate(Terminal.SUCCEEDED).build();
		var w = wrapper(child);
		var file = directory.resolve("return-time");
		try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("step", step)), compatibility())) {
			String id = runtime.start(w, "key", new Text("x")).runId();
			var end = runtime.resume(id, w);
			mutate(file, id, t -> ((ObjectNode) t.path("returns").elements().next()).put("time",
					end.deadline().toEpochMilli() + 1));
			assertRefusesUnchanged(runtime, file, id, w, effects);
		}
	}

	@Test
	void localDeadlineOutcomeCannotClaimUnrelatedStepFailure() throws Exception {
		var effects = new ArrayList<String>();
		var step = new Prefix("F", effects);
		var child = Workflows.define("child")
			.maxDuration(java.time.Duration.ofMillis(100))
			.then(step)
			.terminate(Terminal.SUCCEEDED)
			.build();
		var w = wrapper(child);
		var file = directory.resolve("outcome-cause");
		try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("step", step)), compatibility())) {
			controlledClock(file, 10000);
			String id = runtime.start(w, "key", new Text("x")).runId();
			runtime.advance(id, w);
			time(10100);
			var expired = runtime.inspect(id);
			String childId = expired.scopes()
				.stream()
				.filter(x -> !x.parentScope().isEmpty())
				.findFirst()
				.orElseThrow()
				.id();
			mutate(file, id,
					t -> ((ObjectNode) t.path("scopes").path(childId).path("localOutcome")).put("code", "STEP_FAILED"));
			assertRefusesUnchanged(runtime, file, id, w, effects);
		}
	}

	private static void assertRefusesUnchanged(DurableWorkflows runtime, Path file, String id,
			io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow workflow, List<String> effects)
			throws Exception {
		String corrupted = state(file, id);
		var previousEffects = List.copyOf(effects);
		for (boolean direct : List.of(true, false))
			assertThatThrownBy(() -> {
				if (direct)
					runtime.advance(id, workflow);
				else
					runtime.resume(id, workflow);
			}).isInstanceOfSatisfying(WorkflowRefusal.class, e -> assertThat(e.code()).isEqualTo("PROGRESS_INVALID"));
		assertThat(state(file, id)).isEqualTo(corrupted);
		assertThat(effects).isEqualTo(previousEffects);
	}

}
