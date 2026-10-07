package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.*;
import static io.github.markpollack.workflow.batch.durable.DecisionExecutionTest.*;
import static org.assertj.core.api.Assertions.*;

class DecisionCompositionTest {

	@TempDir
	Path directory;

	@Test
	void nestedChoicesAndCompositesUseOneGraphAndScopedCaptures() {
		var choose = new Choose(Route.RIGHT);
		var nested = new Choose(Route.LEFT);
		var prefix = new Prefix("P");
		var tail = new Prefix("T");
		var finish = new Finish();
		var body = Workflows.define("reusable")
			.decision("inner", nested)
			.when(Route.LEFT)
			.then(prefix)
			.when(Route.RIGHT)
			.then(tail)
			.end()
			.terminate(SUCCEEDED)
			.build();
		var root = Workflows.define("outer")
			.decision("outer", choose)
			.when(Route.LEFT)
			.decision("nested", nested)
			.when(Route.LEFT)
			.then(tail)
			.when(Route.RIGHT)
			.then(prefix)
			.end()
			.when(Route.RIGHT)
			.subWorkflow("first", body)
			.subWorkflow("second", body)
			.end()
			.then(finish)
			.terminate(SUCCEEDED)
			.build();
		var registry = StepRegistry
			.of(Map.of("outer", choose, "nested", nested, "prefix", prefix, "tail", tail, "finish", finish));
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), registry,
				DecisionExecutionTest.deployment())) {
			String id = runtime.start(root, "key", new Text("x")).runId();
			assertThat(runtime.resume(id, root).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(id, root)).isEqualTo(new Reply("PPx"));
			var saved = runtime.inspect(id);
			assertThat(saved.scopes()).hasSize(3);
			assertThat(saved.decisions()).hasSize(3);
			assertThat(nested.calls).isEqualTo(2);
			assertThat(tail.calls).isZero();
		}
	}

	@Test
	void emptyArmAndSoleSurvivingArmPreserveContinuation() {
		for (Route selected : Route.values()) {
			var choose = new Choose(selected);
			var prefix = new Prefix("P");
			var finish = new Finish();
			var w = Workflows.define("empty")
				.decision("choice", choose)
				.when(Route.LEFT)
				.when(Route.RIGHT)
				.then(prefix)
				.end()
				.then(finish)
				.terminate(SUCCEEDED)
				.build();
			try (var runtime = DurableWorkflows.open(directory.resolve("empty" + selected),
					StepRegistry.of(Map.of("choose", choose, "prefix", prefix, "finish", finish)),
					DecisionExecutionTest.deployment())) {
				String id = runtime.start(w, "key", new Text("x")).runId();
				assertThat(runtime.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				assertThat(runtime.result(id, w)).isEqualTo(new Reply(selected == Route.LEFT ? "x" : "Px"));
			}
		}
		var choose = new Choose(Route.LEFT);
		var prefix = new Prefix("P");
		var finish = new Finish();
		var w = Workflows.define("survivor")
			.decision("choice", choose)
			.when(Route.LEFT)
			.then(prefix)
			.when(Route.RIGHT)
			.terminate(FAILED, "business stop")
			.end()
			.then(finish)
			.terminate(SUCCEEDED)
			.build();
		try (var runtime = DurableWorkflows.open(directory.resolve("survivor"),
				StepRegistry.of(Map.of("choose", choose, "prefix", prefix, "finish", finish)),
				DecisionExecutionTest.deployment())) {
			String id = runtime.start(w, "key", new Text("x")).runId();
			assertThat(runtime.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(id, w)).isEqualTo(new Reply("Px"));
		}
	}

	@Test
	void selectedNestedArmCarriesThroughBothLexicalJoins() {
		var outer = new Choose(Route.LEFT);
		var inner = new Choose(Route.RIGHT);
		var left = new Prefix("L");
		var right = new Prefix("R");
		var finish = new Finish();
		var workflow = Workflows.define("nested")
			.decision("outer", outer)
			.when(Route.LEFT)
			.decision("inner", inner)
			.when(Route.LEFT)
			.then(left)
			.when(Route.RIGHT)
			.then(right)
			.end()
			.when(Route.RIGHT)
			.then(left)
			.end()
			.then(finish)
			.terminate(SUCCEEDED)
			.build();
		try (var runtime = DurableWorkflows.open(directory.resolve("nested-selected"),
				StepRegistry.of(Map.of("outer", outer, "inner", inner, "left", left, "right", right, "finish", finish)),
				DecisionExecutionTest.deployment())) {
			String id = runtime.start(workflow, "key", new Text("original")).runId();
			assertThat(runtime.resume(id, workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(id, workflow)).isEqualTo(new Reply("Roriginal"));
			assertThat(runtime.inspect(id).decisions()).hasSize(2);
			assertThat(left.calls).isZero();
			assertThat(right.calls).isEqualTo(1);
		}
	}

}
