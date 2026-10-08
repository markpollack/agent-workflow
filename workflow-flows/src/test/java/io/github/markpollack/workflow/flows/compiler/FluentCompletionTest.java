package io.github.markpollack.workflow.flows.compiler;

import org.junit.jupiter.api.Test;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.Workflows;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.*;
import static org.assertj.core.api.Assertions.*;

class FluentCompletionTest {

	public record Request(String text) {}
	public record Result(String text) {}
	public enum Route { LEFT, RIGHT }

	static final class Copy implements Step<Request, Result> {
		public Result execute(StepContext context, Request input) {
			return new Result(input.text());
		}
	}

	static final class Choose implements Step<Request, Route> {
		public Route execute(StepContext context, Request input) {
			return Route.LEFT;
		}
	}

	static final class Finish implements Step<Result, Result> {
		public Result execute(StepContext context, Result input) {
			return input;
		}
	}

	@Test
	void rootMayEndInAnExplicitlyNonReturningChild() {
		for (var terminal : java.util.List.of(FAILED, CANCELLED)) {
			var child = Workflows.define("stop").then(new Copy()).terminate(terminal, "rejected").build();
			var parent = Workflows.define("parent").subWorkflow("review", child).build();
			assertThat(parent.rootSummary().continues()).isFalse();
			assertThat(parent.definition().nodes()).hasSize(1);
		}
	}

	@Test
	void bothChoiceArmsMayEndInNonReturningChildren() {
		var failed = Workflows.define("failed").then(new Copy()).terminate(FAILED, "rejected").build();
		var cancelled = Workflows.define("cancelled").then(new Copy()).terminate(CANCELLED, "stopped").build();
		var parent = Workflows.define("parent").decision("route", new Choose())
			.when(Route.LEFT).subWorkflow("reject", failed)
			.when(Route.RIGHT).subWorkflow("stop", cancelled).end().build();
		assertThat(parent.rootSummary().continues()).isFalse();
	}

	@Test
	void nonReturningArmDoesNotContributeAnInputToTheContinuingJoin() {
		var child = Workflows.define("stop").then(new Copy()).terminate(FAILED, "rejected").build();
		var parent = Workflows.define("parent").decision("route", new Choose())
			.when(Route.LEFT).subWorkflow("reject", child)
			.when(Route.RIGHT).then(new Copy()).end()
			.then(new Finish()).terminate(SUCCEEDED).build();
		assertThat(parent.definition().output()).isEqualTo(Result.class);
	}

	@Test
	void exposingBuildDoesNotPermitDanglingPathsOrUnreachableSuccessors() {
		assertThatThrownBy(() -> Workflows.define("dangling").then(new Copy()).build())
			.hasMessageContaining("dangling").hasMessageContaining("missing terminal")
			.hasMessageContaining("terminate");
		var child = Workflows.define("stop").then(new Copy()).terminate(FAILED, "rejected").build();
		assertThatThrownBy(() -> Workflows.define("parent").subWorkflow("review", child)
			.then("unreachable", new Finish()).terminate(SUCCEEDED).build())
			.hasMessageContaining("parent").hasMessageContaining("unreachable");
		assertThatThrownBy(() -> Workflows.define("parent").subWorkflow("review", child)
			.terminate(SUCCEEDED).build()).hasMessageContaining("unreachable");
		assertThatThrownBy(() -> Workflows.define("parent").decision("route", new Choose())
			.when(Route.LEFT).subWorkflow("reject", child).then("unreachable", new Finish())
			.when(Route.RIGHT).then(new Copy()).end().terminate(SUCCEEDED).build())
			.hasMessageContaining("route").hasMessageContaining("LEFT").hasMessageContaining("unreachable");
	}

	@Test
	void unfinishedInnerBlockStillPreventsBuild() {
		var parent = Workflows.define("unfinished").then(new Copy());
		parent.decision("route", new Choose());
		assertThatThrownBy(parent::build).hasMessageContaining("unfinished").hasMessageContaining("end()");
	}
}
