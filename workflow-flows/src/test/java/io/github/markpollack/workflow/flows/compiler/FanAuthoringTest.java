package io.github.markpollack.workflow.flows.compiler;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.markpollack.workflow.flows.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static org.assertj.core.api.Assertions.*;

class FanAuthoringTest {

	public record Request(String id) {
	}

	public record Item(String id) {
	}

	public record Reviewed(String id) {
	}

	static class Discover implements Step<Request, List<Item>> {

		public List<Item> execute(StepContext c, Request r) {
			return List.of(new Item(r.id()));
		}

	}

	static class Review implements Step<Item, Reviewed> {

		public Reviewed execute(StepContext c, Item i) {
			return new Reviewed(i.id());
		}

	}

	static class Leak implements Step<Item, Request> {

		public Request execute(StepContext c, Item i) {
			return new Request(i.id());
		}

	}

	@Test
	void fluentAndProgrammaticFanShareAdmissionAndBoundIdentity() {
		var discover = new Discover();
		var review = new Review();
		var fluent = Workflows.define("fan")
			.then("discover", discover)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then("review", review)
			.end()
			.terminate(Terminal.SUCCEEDED)
			.build();
		var source = fluent.definition();
		var raw = ValidatedWorkflow.compile(
				new Definition<>(source.name(), source.input(), source.output(), source.nodes(), null),
				List.of(discover, review), DeadlinePolicy.DEFAULT);
		assertThat(raw.authoredIdentity()).isEqualTo(fluent.authoredIdentity());
		assertThat(raw.graph().bindings()).isEqualTo(fluent.graph().bindings());
		var changed = Workflows.define("fan")
			.then("discover", discover)
			.forEach("items")
			.maxItems(3)
			.maxInFlight(1)
			.allSuccessful()
			.then("review", review)
			.end()
			.terminate(Terminal.SUCCEEDED)
			.build();
		assertThat(changed.authoredIdentity()).isNotEqualTo(fluent.authoredIdentity());
		var changedFlight = Workflows.define("fan")
			.then("discover", discover)
			.forEach("items")
			.maxItems(2)
			.maxInFlight(2)
			.allSuccessful()
			.then("review", review)
			.end()
			.terminate(Terminal.SUCCEEDED)
			.build();
		assertThat(changedFlight.authoredIdentity()).isNotEqualTo(fluent.authoredIdentity());
	}

	@Test
	void missingBoundsPolicyAndEmptyBodyRefuse() {
		var start = Workflows.define("fan").then(new Discover());
		assertThatThrownBy(() -> start.forEach("items").maxItems(0)).hasMessageContaining("positive maxItems").hasMessageContaining("fan").hasMessageContaining("items").hasMessageContaining("exactly once");
		var bounds = Workflows.define("fan").then(new Discover()).forEach("items").maxItems(2);
		assertThatThrownBy(() -> bounds.maxInFlight(0)).hasMessageContaining("positive maxInFlight").hasMessageContaining("items").hasMessageContaining("set maxItems first");
		assertThatThrownBy(() -> Workflows.define("fan")
			.then(new Discover())
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.end()).hasMessageContaining("nonempty");
		for (var fan : List.of(
				new Fan("items", Item.class, 0, 1, true,
						List.of(new Call("review", Op.declared("review", Item.class, Reviewed.class)))),
				new Fan("items", Item.class, 2, 0, true,
						List.of(new Call("review", Op.declared("review", Item.class, Reviewed.class)))),
				new Fan("items", Item.class, 2, 1, false,
						List.of(new Call("review", Op.declared("review", Item.class, Reviewed.class)))))) {
			var raw = new Definition<>("fan", new ListType(Item.class), new ListType(Reviewed.class),
					List.of(fan, new End(Terminal.SUCCEEDED, "")), null);
			assertThatThrownBy(() -> ValidatedWorkflow.compile(raw, List.of(new Review()), DeadlinePolicy.DEFAULT))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void itemPrivateFactsCannotLeakToFollowingStepAndStaleBodyRefuses() {
		assertThatThrownBy(() -> Workflows.define("leak")
			.then(new Discover())
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(new Review())
			.end()
			.then(new Leak())
			.terminate(Terminal.SUCCEEDED)
			.build()).hasMessageContaining("unbound").hasMessageContaining("leak").hasMessageContaining("declared workflow/group result");
		var body = Workflows.define("stale")
			.then(new Discover())
			.forEach("items")
			.maxItems(2)
			.maxInFlight(1)
			.allSuccessful()
			.then(new Review());
		body.end().terminate(Terminal.SUCCEEDED).build();
		assertThatThrownBy(() -> body.then(new Review())).isInstanceOf(IllegalStateException.class).hasMessageContaining("stale").hasMessageContaining("items").hasMessageContaining("end()");
	}

}
