package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;

class FanOutCompositionReviewTest {

	@TempDir
	Path directory;

	public record Request(String name) {
	}

	public record Item(String name) {
	}

	public record Reviewed(String name) {
	}

	public record Context(String name) {
	}

	public record ReviewInput(Item item, Context context) {
	}

	public record Other(String name) {
	}

	static class Discover implements Step<Request, List<Item>> {

		final List<Item> items;

		Discover() {
			this(List.of(new Item("same"), new Item("same")));
		}

		Discover(List<Item> items) {
			this.items = items;
		}

		public List<Item> execute(StepContext c, Request r) {
			return items;
		}

	}

	static class Prepare implements Step<Request, Context> {

		public Context execute(StepContext c, Request r) {
			return new Context(r.name());
		}

	}

	static class Review implements Step<Item, Reviewed> {

		public Reviewed execute(StepContext c, Item i) {
			return new Reviewed(i.name());
		}

	}

	static class ReviewContext implements Step<ReviewInput, Reviewed> {

		public Reviewed execute(StepContext c, ReviewInput i) {
			return new Reviewed(i.item().name() + i.context().name());
		}

	}

	static class DiscoverOther implements Step<Request, List<Other>> {

		public List<Other> execute(StepContext c, Request r) {
			return List.of(new Other("unrelated"));
		}

	}

	enum Route {

		LEFT, RIGHT

	}

	static class ChooseItem implements Step<Item, Route> {

		public Route execute(StepContext c, Item i) {
			return Route.LEFT;
		}

	}

	static class ChooseContext implements Step<ReviewInput, Route> {

		public Route execute(StepContext c, ReviewInput i) {
			if (!i.context().name().equals("context"))
				throw new IllegalStateException("context lost");
			return Route.RIGHT;
		}

	}

	static ExecutionCompatibility deployment() {
		return new ExecutionCompatibility("fan-composition", "1", Map.of());
	}

	@Test
	void historicalTypedManifestCanFeedContextRecordBody() {
		var discover = new Discover();
		var prepare = new Prepare();
		var review = new ReviewContext();
		var w = Workflows.define("historical-manifest")
			.then(discover)
			.then(prepare)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("history"),
				StepRegistry.of(Map.of("discover", discover, "prepare", prepare, "review", review)), deployment())) {
			var id = rt.start(w, "r", new Request("context")).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w)).isEqualTo(List.of(new Reviewed("samecontext"), new Reviewed("samecontext")));
		}
	}

	@Test
	void fanAtEntryInfersListBoundaryFromSimpleBody() {
		var review = new Review();
		var w = Workflows.define("entry-fan")
			.forEach("items")
			.maxItems(2)
			.maxInFlight(2)
			.allSuccessful()
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("entry"), StepRegistry.of(Map.of("review", review)),
				deployment())) {
			var id = rt.start(w, "r", List.of(new Item("a"), new Item("b"))).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w)).isEqualTo(List.of(new Reviewed("a"), new Reviewed("b")));
		}
	}

	@Test
	void unrelatedCurrentCollectionDoesNotReplaceTheOnlyCompatibleManifest() {
		var discover = new Discover();
		var other = new DiscoverOther();
		var review = new Review();
		var w = Workflows.define("compatible-manifest")
			.then(discover)
			.then(other)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("compatible"),
				StepRegistry.of(Map.of("discover", discover, "other", other, "review", review)), deployment())) {
			var id = rt.start(w, "r", new Request("r")).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w)).isEqualTo(List.of(new Reviewed("same"), new Reviewed("same")));
		}
	}

	@Test
	void indistinguishableHistoricalCollectionsRefuseBeforeExecution() {
		var one = new Discover();
		var two = new Discover();
		var context = new Prepare();
		var review = new Review();
		assertThatThrownBy(() -> Workflows.define("ambiguous-manifest")
			.then(one)
			.then(two)
			.then(context)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous");
	}

	@Test
	void decisionOnlyFanBodyPreservesCompatibleHistoricalItemAsCarrier() {
		var discover = new Discover();
		var other = new DiscoverOther();
		var choose = new ChooseItem();
		var w = Workflows.define("decision-only-items")
			.then(discover)
			.then(other)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.decision("route", choose)
			.when(Route.LEFT)
			.when(Route.RIGHT)
			.end()
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("decision-items"),
				StepRegistry.of(Map.of("discover", discover, "other", other, "choose", choose)), deployment())) {
			var id = rt.start(w, "r", new Request("r")).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w)).isEqualTo(List.of(new Item("same"), new Item("same")));
		}
	}

	@Test
	void assembledDecisionControlInputDoesNotReplaceTheFanItemCarrier() {
		var discover = new Discover();
		var context = new Prepare();
		var choose = new ChooseContext();
		var w = Workflows.define("decision-context-items")
			.then(discover)
			.then(context)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.decision("route", choose)
			.when(Route.LEFT)
			.when(Route.RIGHT)
			.end()
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("decision-context"),
				StepRegistry.of(Map.of("discover", discover, "context", context, "choose", choose)), deployment())) {
			var id = rt.start(w, "r", new Request("context")).runId();
			assertThat(rt.resume(id, w).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(rt.result(id, w)).isEqualTo(List.of(new Item("same"), new Item("same")));
		}
	}

	@Test
	void compositeOccurrenceMultiplierCountsItsInnerStaticScopes() {
		var discover = new Discover();
		var review = new Review();
		var child = Workflows.define("per-item")
			.parallel("checks")
			.allSuccessful()
			.branch("one")
			.then(review)
			.branch("two")
			.then(review)
			.end()
			.terminate(SUCCEEDED)
			.build();
		var w = Workflows.define("outer")
			.then(discover)
			.forEach("items")
			.maxItems(3)
			.maxInFlight(1)
			.allSuccessful()
			.subWorkflow("body", child)
			.end()
			.terminate(SUCCEEDED)
			.build();
		var prepared = new WorkflowExecutionBindings(StepRegistry.of(Map.of("discover", discover, "review", review)),
				deployment(), w, new ExecutionPolicy(3, 32, 10, 1));
		assertThat(prepared.bounds().leaves()).isEqualTo(7);
		assertThat(prepared.bounds().composites()).isEqualTo(3);
		assertThat(prepared.bounds().scopes()).isEqualTo(13);
		assertThatThrownBy(
				() -> new WorkflowExecutionBindings(StepRegistry.of(Map.of("discover", discover, "review", review)),
						deployment(), w, new ExecutionPolicy(3, 32, 9, 1)))
			.isInstanceOf(WorkflowRefusal.class)
			.hasMessageContaining("bound");
	}

	@Test
	void nestedFanInStaticMembersRetainsAllEqualOccurrencesAtCapacityOne() {
		var discover = new Discover();
		var second = new Discover(List.of(new Item("second")));
		var review = new Review();
		var w = Workflows.define("static-fans")
			.parallel("outer")
			.allSuccessful()
			.branch("first")
			.then(discover)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.branch("second")
			.then(second)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(review)
			.end()
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var rt = DurableWorkflows.open(directory.resolve("static"),
				StepRegistry.of(Map.of("discover", discover, "second", second, "review", review)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 1))) {
			var id = rt.start(w, "r", new Request("r")).runId();
			var end = rt.resume(id, w);
			assertThat(end.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(end.invocations()).hasSize(5);
			assertThat(end.groups()).hasSize(3);
			assertThat(rt.result(id, w)).isEqualTo(
					List.of(List.of(new Reviewed("same"), new Reviewed("same")), List.of(new Reviewed("second"))));
		}
	}

}
