package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.batch.examples.FanOutReviewExample;
import io.github.markpollack.workflow.batch.examples.FanOutReviewExample.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;

class FanOutExecutionTest {

	@TempDir
	Path directory;

	static ExecutionCompatibility deployment() {
		return new ExecutionCompatibility("fan-tests", "1", Map.of());
	}

	static class Discovery extends DiscoverFiles {

		final List<FilePatch> items;

		int calls;

		Discovery(List<FilePatch> items) {
			this.items = items;
		}

		public List<FilePatch> execute(StepContext c, ReviewRequest r) {
			calls++;
			return items;
		}

	}

	static class Review extends ReviewFile {

		final List<String> ids = new CopyOnWriteArrayList<>();

		final List<FilePatch> inputs = new CopyOnWriteArrayList<>();

		public FileReview execute(StepContext c, FilePatch p) {
			ids.add(c.invocationId());
			inputs.add(p);
			return super.execute(c, p);
		}

	}

	@Test
	void compilingExamplePreservesTypedInputsAndEarlierContext() {
		var discover = new DiscoverFiles();
		var review = new Review();
		var report = new WriteReport();
		var w = FanOutReviewExample.workflow(discover, review, report);
		try (var rt = DurableWorkflows.open(directory.resolve("example"),
				StepRegistry.of(Map.of("discover", discover, "review", review, "report", report)), deployment())) {
			var id = rt.start(w, "request", new ReviewRequest("sha-42")).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w)).isEqualTo(
					new Report("sha-42", List.of(new FileReview("A.java", true), new FileReview("B.java", false))));
			assertThat(review.inputs).containsExactlyInAnyOrder(new FilePatch("A.java", "sha-42"),
					new FilePatch("B.java", "TODO"));
		}
	}

	@Test
	void emptyDuplicatesAndOversizeAreExplicit() {
		var p = new FilePatch("same", "TODO");
		for (var items : List.of(List.<FilePatch>of(), List.of(p, p), List.of(p, p, p))) {
			var discover = new Discovery(items);
			var review = new Review();
			var report = new WriteReport();
			var w = Workflows.define("cardinality")
				.then(discover)
				.forEach("items")
				.maxItems(2)
				.maxInFlight(1)
				.allSuccessful()
				.then(review)
				.end()
				.then(report)
				.terminate(SUCCEEDED)
				.build();
			try (var rt = DurableWorkflows.open(directory.resolve("size-" + items.size()),
					StepRegistry.of(Map.of("discover", discover, "review", review, "report", report)), deployment())) {
				var id = rt.start(w, "request", new ReviewRequest("r")).runId();
				var end = rt.resume(id, w);
				if (items.size() > 2) {
					assertThat(end.reason().code()).isEqualTo("MAX_ITEMS_EXCEEDED");
					assertThat(review.inputs).isEmpty();
				}
				else {
					assertThat(end.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
					assertThat(rt.result(id, w))
						.isEqualTo(new Report("r", items.stream().map(x -> new FileReview("same", false)).toList()));
					assertThat(review.ids).doesNotHaveDuplicates().hasSize(items.size());
					assertThat(end.groups().getFirst().members()).hasSize(items.size());
				}
			}
		}
	}

	static class FailFirst extends Review {

		public FileReview execute(StepContext c, FilePatch p) {
			super.execute(c, p);
			if (p.path().equals("bad"))
				throw new IllegalStateException("review broke");
			return new FileReview(p.path(), false);
		}

	}

	@Test
	void executionFailureSettlesAdmittedAndQueuedItemsWithoutJoin() {
		var discover = new Discovery(
				List.of(new FilePatch("bad", ""), new FilePatch("good", ""), new FilePatch("last", "")));
		var review = new FailFirst();
		var report = new WriteReport();
		var w = Workflows.define("failed")
			.then(discover)
			.forEach("files")
			.maxItems(3)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.then(report)
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("failed"),
				StepRegistry.of(Map.of("discover", discover, "review", review, "report", report)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 2))) {
			var id = rt.start(w, "r", new ReviewRequest("r")).runId();
			var end = rt.resume(id, w);
			assertThat(end.reason().code()).isEqualTo("PARALLEL_FAILED");
			assertThat(review.inputs).hasSize(3);
			assertThat(end.groups().getFirst().members().stream().map(RunSnapshot.Member::status))
				.containsExactly("FAILED", "SUCCEEDED", "SUCCEEDED");
			assertThat(end.invocations()).hasSize(4);
		}
	}

	static class BlockFirst extends Review {

		final CountDownLatch secondDone = new CountDownLatch(1);

		public FileReview execute(StepContext c, FilePatch p) {
			if (p.path().equals("first"))
				ParallelExecutionTest.await(secondDone);
			return super.execute(c, p);
		}

	}

	@Test
	void reverseCompletionStillCollectsInManifestOrder() {
		var discover = new Discovery(List.of(new FilePatch("first", ""), new FilePatch("second", "TODO")));
		var review = new BlockFirst();
		var report = new WriteReport();
		var w = Workflows.define("ordered")
			.then(discover)
			.forEach("files")
			.maxItems(2)
			.maxInFlight(2)
			.allSuccessful()
			.then(review)
			.end()
			.then(report)
			.terminate(SUCCEEDED)
			.build();
		Path file = directory.resolve("ordered");
		BoundaryHooks hooks = (point, id) -> {
			if (point.equals("AFTER_RESULT_COMMIT"))
				try {
					var state = new com.fasterxml.jackson.databind.ObjectMapper()
						.readTree(StoreTestSupport.state(file, id));
					for (var call : state.path("invocations")) {
						var value = state.path("values").path(call.path("output").asText());
						if (call.path("outcome").asText().equals("COMMITTED")
								&& value.path("type").asText().equals(FileReview.class.getName())
								&& new String(Base64.getDecoder().decode(value.path("payload").asText()))
									.contains("second"))
							review.secondDone.countDown();
					}
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
		};
		try (var rt = new DurableWorkflows(file,
				StepRegistry.of(Map.of("discover", discover, "review", review, "report", report)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 2), hooks)) {
			var id = rt.start(w, "r", new ReviewRequest("r")).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(review.inputs.stream().map(FilePatch::path)).containsExactly("second", "first");
			assertThat(rt.result(id, w))
				.isEqualTo(new Report("r", List.of(new FileReview("first", true), new FileReview("second", false))));
		}
		finally {
			review.secondDone.countDown();
		}
	}

	public record FinishedReview(String path) {
	}

	static class BlockingFinish implements Step<FileReview, FinishedReview> {

		final CountDownLatch entered, release = new CountDownLatch(1);

		final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();

		BlockingFinish(int expected) {
			entered = new CountDownLatch(expected);
		}

		public FinishedReview execute(StepContext c, FileReview r) {
			int count = active.incrementAndGet();
			peak.accumulateAndGet(count, Math::max);
			entered.countDown();
			try {
				ParallelExecutionTest.await(release);
				return new FinishedReview(r.path());
			}
			finally {
				active.decrementAndGet();
			}
		}

	}

	@Test
	void logicalWholeBodyOccupancyAndPhysicalCapacityAreSeparate() throws Exception {
		for (int[] bounds : List.of(new int[] { 1, 3 }, new int[] { 3, 1 }, new int[] { 3, 2 })) {
			int logical = bounds[0], physical = bounds[1];
			var discover = new Discovery(List.of(new FilePatch("a", ""), new FilePatch("b", ""), new FilePatch("c", ""),
					new FilePatch("d", "")));
			var review = new Review();
			var finish = new BlockingFinish(Math.min(logical, physical));
			var w = Workflows.define("bounded")
				.then(discover)
				.forEach("files")
				.maxItems(4)
				.maxInFlight(logical)
				.allSuccessful()
				.then(review)
				.then(finish)
				.end()
				.terminate(SUCCEEDED)
				.build();
			try (var rt = DurableWorkflows.open(directory.resolve("bounds-" + logical + "-" + physical),
					StepRegistry.of(Map.of("discover", discover, "review", review, "finish", finish)), deployment(),
					new ExecutionPolicy(3, 32, 10000, physical)); var callers = Executors.newSingleThreadExecutor()) {
				var id = rt.start(w, "r", new ReviewRequest("r")).runId();
				var end = callers.submit(() -> rt.resume(id, w));
				try {
					assertThat(finish.entered.await(10, TimeUnit.SECONDS)).isTrue();
					var saved = rt.inspect(id);
					var members = saved.groups().getFirst().members();
					assertThat(members.stream().filter(m -> m.status().equals("OPEN")).count()).isEqualTo(logical);
					assertThat(members.stream().filter(m -> m.status().equals("QUEUED")).count())
						.isEqualTo(4 - logical);
					assertThat(finish.active.get()).isEqualTo(Math.min(logical, physical));
					if (logical == 1)
						assertThat(review.inputs).hasSize(1); // first leaf completed,
																// whole item still
																// occupies its slot
					assertThatThrownBy(() -> rt.resume(id, w)).isInstanceOf(WorkflowRefusal.class)
						.hasMessageContaining("already executing");
				}
				finally {
					finish.release.countDown();
				}
				assertThat(end.get(20, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				assertThat(finish.peak.get()).isLessThanOrEqualTo(physical);
				assertThat(rt.result(id, w)).isEqualTo(List.of(new FinishedReview("a"), new FinishedReview("b"),
						new FinishedReview("c"), new FinishedReview("d")));
			}
		}
	}

	enum Route {

		LEFT, RIGHT

	}

	static class Choose implements Step<FilePatch, Route> {

		public Route execute(StepContext c, FilePatch p) {
			return p.path().equals("a") ? Route.LEFT : Route.RIGHT;
		}

	}

	static class Collapse implements Step<List<FileReview>, FileReview> {

		public FileReview execute(StepContext c, List<FileReview> r) {
			return r.getFirst();
		}

	}

	@Test
	void choicesStaticGroupsAndReusableItemsProgressAtCapacityOne() {
		var review = new Review();
		var choose = new Choose();
		var collapse = new Collapse();
		var item = Workflows.define("item-review")
			.decision("route", choose)
			.when(Route.LEFT)
			.parallel("checks")
			.allSuccessful()
			.branch("one")
			.then(review)
			.branch("two")
			.then(review)
			.end()
			.then(collapse)
			.when(Route.RIGHT)
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		var discover = new Discovery(List.of(new FilePatch("a", ""), new FilePatch("b", "TODO")));
		var report = new WriteReport();
		var w = Workflows.define("nested")
			.then(discover)
			.forEach("files")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.subWorkflow("review", item)
			.end()
			.then(report)
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(
				directory.resolve("nested"), StepRegistry.of(Map.of("discover", discover, "review", review, "choose",
						choose, "collapse", collapse, "report", report)),
				deployment(), new ExecutionPolicy(3, 32, 10000, 1))) {
			var id = rt.start(w, "r", new ReviewRequest("r")).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w))
				.isEqualTo(new Report("r", List.of(new FileReview("a", true), new FileReview("b", false))));
		}
	}

	public record Part(String path) {
	}

	public record PartReview(String path) {
	}

	public record PartsInput(FilePatch patch, List<PartReview> parts) {
	}

	static class Split implements Step<FilePatch, List<Part>> {

		public List<Part> execute(StepContext c, FilePatch p) {
			return List.of(new Part(p.path() + "-1"), new Part(p.path() + "-2"));
		}

	}

	static class ReviewPart implements Step<Part, PartReview> {

		public PartReview execute(StepContext c, Part p) {
			return new PartReview(p.path());
		}

	}

	static class Recombine implements Step<PartsInput, FileReview> {

		public FileReview execute(StepContext c, PartsInput p) {
			if (!p.parts()
				.equals(List.of(new PartReview(p.patch().path() + "-1"), new PartReview(p.patch().path() + "-2"))))
				throw new IllegalStateException("item isolation lost");
			return new FileReview(p.patch().path(), true);
		}

	}

	@Test
	void nestedFanTemplatesKeepDistinctItemCoordinatesAtCapacityOne() {
		var discover = new Discovery(List.of(new FilePatch("a", ""), new FilePatch("b", "")));
		var split = new Split();
		var review = new ReviewPart();
		var recombine = new Recombine();
		var report = new WriteReport();
		var w = Workflows.define("nested-fan")
			.then(discover)
			.forEach("files")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(split)
			.forEach("parts")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.then(recombine)
			.end()
			.then(report)
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(
				directory.resolve("nested-fan"), StepRegistry.of(Map.of("discover", discover, "split", split, "review",
						review, "recombine", recombine, "report", report)),
				deployment(), new ExecutionPolicy(3, 32, 10000, 1))) {
			var id = rt.start(w, "r", new ReviewRequest("r")).runId();
			var end = rt.resume(id, w);
			assertThat(end.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w))
				.isEqualTo(new Report("r", List.of(new FileReview("a", true), new FileReview("b", true))));
			assertThat(end.groups()).hasSize(3);
			assertThat(end.resources().leafBound()).isEqualTo(10);
			assertThat(end.resources().scopeBound()).isEqualTo(7);
		}
	}

}
