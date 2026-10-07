package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import static io.github.markpollack.workflow.batch.durable.DecisionExecutionTest.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.*;
import static org.assertj.core.api.Assertions.*;

class DecisionIntegrityTest {

	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = { "missing-route", "wrong-route", "wrong-target", "missing-arm-input", "wrong-arm-input",
			"wrong-capture-source", "missing-capture" })
	void changedOrMissingDecisionFactsRefuseBeforeFurtherEffects(String mutation) throws Exception {
		var choose = new Choose(Route.RIGHT);
		var left = new Prefix("L");
		var right = new Prefix("R");
		var finish = new Finish();
		var workflow = Workflows.define("integrity")
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
		Path file = directory.resolve("runs");
		String id;
		try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
			id = runtime.start(workflow, "key", new Text("x")).runId();
			runtime.advance(id, workflow);
			if (mutation.contains("capture"))
				runtime.advance(id, workflow);
		}
		StoreTestSupport.mutate(file, id, state -> {
			var decisions = (ObjectNode) state.get("decisions");
			var receipt = (ObjectNode) decisions.elements().next();
			var values = (ObjectNode) state.get("values");
			switch (mutation) {
				case "missing-route" -> decisions.removeAll();
				case "wrong-route" -> receipt.put("outcome", "LEFT");
				case "wrong-target" -> receipt.put("target", "unknown");
				case "missing-arm-input" -> values.remove(receipt.get("armInput").asText());
				case "wrong-arm-input" -> receipt.put("armInput",
						state.get("scopes").get(state.get("rootScope").asText()).get("input").asText() + "wrong");
				case "wrong-capture-source" -> {
					var captures = (ObjectNode) receipt.get("captures");
					captures.put(captures.fieldNames().next(), "unknown");
				}
				case "missing-capture" -> values.remove(receipt.get("captures").fieldNames().next());
			}
		});
		int before = left.calls + right.calls + choose.calls;
		assertThatThrownBy(() -> {
			try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
				runtime.resume(id, workflow);
			}
		}).isInstanceOf(WorkflowRefusal.class);
		assertThat(left.calls + right.calls + choose.calls).isEqualTo(before);
	}

}
