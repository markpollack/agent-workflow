package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.batch.examples.FanOutReviewExample.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static io.github.markpollack.workflow.batch.durable.FanOutExecutionTest.*;
import static org.assertj.core.api.Assertions.*;

class FanOutLifecycleTest {

	@TempDir
	Path directory;

	static class BlockSecond extends Review {

		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

		public FileReview execute(StepContext c, FilePatch p) {
			if (p.path().equals("b")) {
				entered.countDown();
				ParallelExecutionTest.await(release);
			}
			return super.execute(c, p);
		}

	}

	@Test
	void cancellationPreservesCompletedItemAndAccountsForQueuedItems() throws Exception {
		var discover = new Discovery(List.of(new FilePatch("a", ""), new FilePatch("b", ""), new FilePatch("c", "")));
		var review = new BlockSecond();
		var w = Workflows.define("reviewer-cancel")
			.then(discover)
			.forEach("files")
			.maxItems(3)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("cancel"),
				StepRegistry.of(Map.of("discover", discover, "review", review)), deployment());
				var callers = Executors.newSingleThreadExecutor()) {
			var id = rt.start(w, "r", new ReviewRequest("r")).runId();
			var end = callers.submit(() -> rt.resume(id, w));
			try {
				assertThat(review.entered.await(10, TimeUnit.SECONDS)).isTrue();
				var cancelled = rt.cancel(id, "reviewer", "cancel");
				assertThat(cancelled.groups().getFirst().members().stream().map(RunSnapshot.Member::status))
					.containsExactly("SUCCEEDED", "REVOKED", "REVOKED");
				assertThat(cancelled.groups().getFirst().members().stream().map(RunSnapshot.Member::code))
					.containsExactly("MEMBER_SUCCEEDED", "CANCELLED", "CANCELLED");
			}
			finally {
				review.release.countDown();
			}
			assertThat(end.get(20, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.CANCELLED);
			assertThat(rt.inspect(id).invocations()).hasSize(3);
			assertThat(review.inputs.stream().map(FilePatch::path)).containsExactly("a", "b");
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.CANCELLED);
		}
	}

	@Test
	void committedFeederIsIndependentFromLiveCollectionAndSavedItemProvenanceRefuses() throws Exception {
		for (boolean corrupt : List.of(false, true)) {
			var mutable = new ArrayList<>(List.of(new FilePatch("a", ""), new FilePatch("b", "TODO")));
			var discover = new Discovery(mutable);
			var review = new Review();
			var w = Workflows.define("reviewer-snapshot")
				.then(discover)
				.forEach("files")
				.maxItems(2)
				.maxInFlight(1)
				.allSuccessful()
				.then(review)
				.end()
				.terminate(SUCCEEDED)
				.build();
			var file = directory.resolve("saved-" + corrupt);
			String id;
			var registry = StepRegistry.of(Map.of("discover", discover, "review", review));
			try (var rt = DurableWorkflows.open(file, registry, deployment())) {
				id = rt.start(w, "r", new ReviewRequest("r")).runId();
				rt.advance(id, w);
				rt.advance(id, w);
				assertThat(rt.inspect(id).groups().getFirst().members()).hasSize(2);
			}
			mutable.clear();
			mutable.add(new FilePatch("changed", ""));
			if (corrupt)
				StoreTestSupport.mutate(file, id, state -> {
					var group = state.path("groups").elements().next();
					var item = group.path("members").get(0).asText();
					for (var value : state.path("values"))
						if (value.path("scope").asText().equals(item))
							((ObjectNode) value).put("source", "missing-manifest");
				});
			if (corrupt) {
				assertThatThrownBy(() -> {
					try (var rt = DurableWorkflows.open(file, registry, deployment())) {
						rt.resume(id, w);
					}
				}).isInstanceOf(WorkflowRefusal.class);
				assertThat(review.inputs).isEmpty();
			}
			else
				try (var rt = DurableWorkflows.open(file, registry, deployment())) {
					assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
					assertThat(rt.result(id, w))
						.isEqualTo(List.of(new FileReview("a", true), new FileReview("b", false)));
					assertThat(discover.calls).isEqualTo(1);
				}
		}
	}

}
