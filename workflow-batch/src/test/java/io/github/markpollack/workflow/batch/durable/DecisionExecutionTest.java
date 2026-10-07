package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.*;
import static org.assertj.core.api.Assertions.*;

class DecisionExecutionTest {

	@TempDir
	Path directory;

	public record Text(String value) {
	}

	public record Reply(String value) {
	}

	enum Route {

		LEFT, RIGHT

	}

	static final class Choose implements Step<Text, Route> {

		int calls;

		final Route route;

		Choose(Route route) {
			this.route = route;
		}

		public Route execute(StepContext context, Text input) {
			calls++;
			return route;
		}

	}

	static final class Prefix implements Step<Text, Text> {

		final String prefix;

		int calls;

		Prefix(String prefix) {
			this.prefix = prefix;
		}

		public Text execute(StepContext context, Text input) {
			calls++;
			return new Text(prefix + input.value());
		}

	}

	static final class Finish implements Step<Text, Reply> {

		public Reply execute(StepContext context, Text input) {
			return new Reply(input.value());
		}

	}

	static ExecutionCompatibility deployment() {
		return new ExecutionCompatibility("decisions", "v1", Map.of());
	}

	@Test
	void terminalArmsUseEarlierInputAndExecuteOnlySelectedArm() {
		for (Route selected : Route.values()) {
			var choose = new Choose(selected);
			var left = new Prefix("L");
			var right = new Prefix("R");
			var finish = new Finish();
			var workflow = Workflows.define("reply")
				.decision("route", choose)
				.when(Route.LEFT)
				.then(left)
				.then(finish)
				.terminate(SUCCEEDED)
				.when(Route.RIGHT)
				.then(right)
				.then(finish)
				.terminate(SUCCEEDED)
				.end()
				.build();
			try (var runtime = DurableWorkflows.open(directory.resolve(selected.name()),
					StepRegistry.of(Map.of("choose", choose, "left", left, "right", right, "finish", finish)),
					deployment())) {
				var run = runtime.start(workflow, "key", new Text("x"));
				var result = runtime.resume(run.runId(), workflow);
				assertThat(result.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				assertThat(runtime.result(run.runId(), workflow))
					.isEqualTo(new Reply((selected == Route.LEFT ? "L" : "R") + "x"));
				assertThat(choose.calls).isEqualTo(1);
				assertThat(left.calls).isEqualTo(selected == Route.LEFT ? 1 : 0);
				assertThat(right.calls).isEqualTo(selected == Route.RIGHT ? 1 : 0);
			}
		}
	}

	@Test
	void continuingArmsCaptureSelectedExactValueAndRecoverWithoutDecisionReplay() {
		var choose = new Choose(Route.RIGHT);
		var left = new Prefix("L");
		var right = new Prefix("R");
		var finish = new Finish();
		var workflow = Workflows.define("capture")
			.decision("route", choose)
			.when(Route.LEFT)
			.then(left)
			.when(Route.RIGHT)
			.then(right)
			.end()
			.then(finish)
			.terminate(SUCCEEDED)
			.build();
		var registry = StepRegistry.of(Map.of("choose", choose, "left", left, "right", right, "finish", finish));
		Path file = directory.resolve("capture");
		String id;
		try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
			id = runtime.start(workflow, "key", new Text("x")).runId();
			var saved = runtime.advance(id, workflow);
			assertThat(saved.invocations()).hasSize(2);
			assertThat(saved.invocations().getLast().attempts()).isEmpty();
			assertThat(left.calls + right.calls).isZero();
		}
		try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
			var result = runtime.resume(id, workflow);
			assertThat(result.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(id, workflow)).isEqualTo(new Reply("Rx"));
			assertThat(choose.calls).isEqualTo(1);
			assertThat(left.calls).isZero();
			assertThat(right.calls).isEqualTo(1);
			assertThat(result.values().stream().filter(v -> !v.sourceValue().isEmpty())).hasSize(1);
		}
	}

	@Test
	void exhaustiveAndStaleArmValidation() {
		var choose = new Choose(Route.LEFT);
		var left = new Prefix("L");
		assertThatThrownBy(() -> Workflows.define("missing")
			.decision("route", choose)
			.when(Route.LEFT)
			.then(left)
			.terminate(SUCCEEDED)
			.end()
			.build()).hasMessageContaining("missing choice outcome");
		var block = Workflows.define("stale").decision("route", choose);
		var old = block.when(Route.LEFT);
		block.when(Route.RIGHT);
		assertThatThrownBy(() -> old.then(left)).isInstanceOf(IllegalStateException.class);
	}

}
