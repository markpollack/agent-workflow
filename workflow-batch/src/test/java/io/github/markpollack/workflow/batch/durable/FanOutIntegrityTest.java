package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.batch.examples.FanOutReviewExample.*;
import static io.github.markpollack.workflow.batch.durable.FanOutExecutionTest.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;

class FanOutIntegrityTest {

	@TempDir
	Path directory;

	@Test
	void changedManifestCoordinatesBytesAndOccupancyRefuseBeforeEffects() throws Exception {
		for (String change : List.of("source", "bounds", "order", "bytes", "occupancy")) {
			var discover = new Discovery(List.of(new FilePatch("a", ""), new FilePatch("b", "")));
			var review = new Review();
			var w = Workflows.define("integrity")
				.then(discover)
				.forEach("items")
				.maxItems(2)
				.maxInFlight(1)
				.allSuccessful()
				.then(review)
				.end()
				.terminate(SUCCEEDED)
				.build();
			Path file = directory.resolve(change);
			String id;
			var registry = StepRegistry.of(Map.of("discover", discover, "review", review));
			try (var rt = DurableWorkflows.open(file, registry, deployment())) {
				id = rt.start(w, "r", new ReviewRequest("r")).runId();
				rt.advance(id, w);
				rt.advance(id, w);
			}
			StoreTestSupport.mutate(file, id, state -> {
				var group = (ObjectNode) state.path("groups").elements().next();
				var members = (ArrayNode) group.path("members");
				var first = state.path("scopes").path(members.get(0).asText());
				var second = (ObjectNode) state.path("scopes").path(members.get(1).asText());
				switch (change) {
					case "source" -> group.put("manifest", first.path("input").asText());
					case "bounds" -> group.put("maxInFlight", 2);
					case "order" -> {
						var a = members.get(0);
						var b = members.get(1);
						members.set(0, b);
						members.set(1, a);
					}
					case "bytes" -> {
						for (var value : state.path("values"))
							if (value.path("scope").asText().equals(members.get(0).asText())) {
								byte[] bytes = "{\"path\":\"changed\",\"diff\":\"\"}"
									.getBytes(java.nio.charset.StandardCharsets.UTF_8);
								((ObjectNode) value).put("payload", Base64.getEncoder().encodeToString(bytes));
								((ObjectNode) value).put("digest", Digests.of(bytes));
							}
					}
					case "occupancy" -> {
						second.put("lifecycle", "OPEN");
						second.set("nodes", first.path("nodes").deepCopy());
					}
				}
			});
			assertThatThrownBy(() -> {
				try (var rt = DurableWorkflows.open(file, registry, deployment())) {
					rt.resume(id, w);
				}
			}).isInstanceOf(WorkflowRefusal.class);
			assertThat(review.inputs).isEmpty();
		}
	}

	@Test
	void rootDeadlineSettlesActiveAndNeverAdmittedItems() throws Exception {
		var discover = new Discovery(List.of(new FilePatch("a", ""), new FilePatch("b", "")));
		var review = new Review();
		var w = Workflows.define("expiry")
			.then(discover)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		Path file = directory.resolve("expiry");
		try (var rt = DurableWorkflows.open(file, StepRegistry.of(Map.of("discover", discover, "review", review)),
				deployment())) {
			StoreTestSupport.controlledClock(file, 1000);
			var id = rt.start(w, "r", new ReviewRequest("r")).runId();
			rt.advance(id, w);
			rt.advance(id, w);
			StoreTestSupport.time(rt.inspect(id).deadline().toEpochMilli());
			var end = rt.resume(id, w);
			assertThat(end.reason().code()).isEqualTo("DEADLINE_EXCEEDED");
			assertThat(end.groups().getFirst().members()).allMatch(m -> m.code().equals("DEADLINE_EXCEEDED"));
			assertThat(review.inputs).isEmpty();
		}
	}

	@Test
	void aggregatePayloadMustMatchCommittedItemsInManifestOrder() throws Exception {
		var discover = new Discovery(List.of(new FilePatch("a", ""), new FilePatch("b", "TODO")));
		var review = new Review();
		var w = Workflows.define("aggregate-integrity")
			.then(discover)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		Path file = directory.resolve("aggregate");
		String id;
		var registry = StepRegistry.of(Map.of("discover", discover, "review", review));
		try (var rt = DurableWorkflows.open(file, registry, deployment())) {
			id = rt.start(w, "r", new ReviewRequest("r")).runId();
			rt.resume(id, w);
		}
		StoreTestSupport.mutate(file, id, state -> {
			var output = (ObjectNode) state.path("values").path(state.path("output").asText());
			byte[] reordered = new io.github.markpollack.workflow.flows.compiler.TypeContracts().encodeBytes(
					List.of(new FileReview("b", false), new FileReview("a", true)),
					new io.github.markpollack.workflow.flows.compiler.WorkflowModel.ListType(FileReview.class));
			output.put("payload", Base64.getEncoder().encodeToString(reordered));
			output.put("digest", Digests.of(reordered));
		});
		assertThatThrownBy(() -> {
			try (var rt = DurableWorkflows.open(file, registry, deployment())) {
				rt.result(id, w);
			}
		}).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("manifest-order aggregate bytes");
	}

}
