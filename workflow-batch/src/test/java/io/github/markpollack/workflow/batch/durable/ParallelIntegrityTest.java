package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.ParallelExecutionTest.*;

class ParallelIntegrityTest {

	@TempDir
	Path directory;

	@Test
	void changedMembershipBoundariesAndSettlementRefuseBeforeEffects() throws Exception {
		for (String corruption : List.of("membership", "entry", "join", "result", "bound", "phase")) {
			var a = new Echo("A");
			var b = new Echo("B");
			var aggregate = new Aggregate();
			var workflow = Workflows.define("integrity")
				.parallel("checks")
				.allSuccessful()
				.branch("a")
				.then(a)
				.branch("b")
				.then(b)
				.end()
				.then(aggregate)
				.terminate(SUCCEEDED)
				.build();
			var registry = StepRegistry.of(Map.of("a", a, "b", b, "aggregate", aggregate));
			var file = directory.resolve(corruption);
			String id;
			try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
				id = runtime.start(workflow, corruption, new Text("x")).runId();
				runtime.advance(id, workflow);
			}
			StoreTestSupport.mutate(file, id, state -> {
				var group = (ObjectNode) state.path("groups").elements().next();
				var members = (ArrayNode) group.path("members");
				var scope = (ObjectNode) state.path("scopes").path(members.get(0).asText());
				switch (corruption) {
					case "membership" -> members.set(0, members.get(1));
					case "entry" ->
						scope.put("entry", state.path("scopes").path(members.get(1).asText()).path("entry").asText());
					case "join" -> group.put("join", scope.path("entry").asText());
					case "result" -> group.put("phase", "SETTLED");
					case "bound" -> scope.put("deadline", scope.path("deadline").asLong() + 1);
					case "phase" -> group.put("phase", "UNKNOWN");
				}
			});
			assertThatThrownBy(() -> {
				try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
					runtime.resume(id, workflow);
				}
			}).isInstanceOf(WorkflowRefusal.class);
			assertThat(a.calls).isZero();
			assertThat(b.calls).isZero();
		}
	}

}
