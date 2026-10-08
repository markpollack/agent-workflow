package io.github.markpollack.workflow.flows.compiler;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.markpollack.workflow.flows.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;

class ParallelAuthoringTest {

	public record Text(String text) {
	}

	public record A(String text) {
	}

	public record B(String text) {
	}

	public record Roles(A a, B b) {
	}

	public record Wrapper(Text text) {
	}

	enum Route {

		LEFT, RIGHT

	}

	static class Echo implements Step<Text, Text> {

		public Text execute(StepContext c, Text t) {
			return t;
		}

	}

	static class MakeA implements Step<Text, A> {

		public A execute(StepContext c, Text t) {
			return new A(t.text());
		}

	}

	static class MakeB implements Step<Text, B> {

		public B execute(StepContext c, Text t) {
			return new B(t.text());
		}

	}

	static class Finish implements Step<Roles, Text> {

		public Text execute(StepContext c, Roles r) {
			return new Text(r.a().text() + r.b().text());
		}

	}

	static class Choose implements Step<Text, Route> {

		public Route execute(StepContext c, Text t) {
			return Route.LEFT;
		}

	}

	static class WrappedChoice implements Step<Wrapper, Route> {

		public Route execute(StepContext c, Wrapper t) {
			return Route.LEFT;
		}

	}

	@Test
	void fluentAndProgrammaticConstructionShareGraphBindingsAndIdentity() {
		var a = new MakeA();
		var b = new MakeB();
		var finish = new Finish();
		var fluent = Workflows.define("roles")
			.parallel("checks")
			.allSuccessful()
			.branch("a")
			.then("make-a", a)
			.branch("b")
			.then("make-b", b)
			.end()
			.then("finish", finish)
			.terminate(SUCCEEDED)
			.build();
		var definition = new Definition<>(
				"roles", Text.class, Text.class, List.of(
						new Parallel(
								"checks", null, true, List.of(
										new Member("a",
												List.of(new Call("make-a",
														Op.declared("make-a", Text.class, A.class)))),
										new Member("b",
												List.of(new Call("make-b",
														Op.declared("make-b", Text.class, B.class)))))),
						new Call("finish", Op.declared("finish", Roles.class, Text.class)), new End(SUCCEEDED, "")),
				null);
		var raw = ValidatedWorkflow.compile(definition, List.of(a, b, finish), DeadlinePolicy.DEFAULT);
		assertThat(raw.authoredIdentity()).isEqualTo(fluent.authoredIdentity());
		assertThat(raw.graph().bindings()).isEqualTo(fluent.graph().bindings());
		assertThat(raw.graph().edges()).isEqualTo(fluent.graph().edges());
	}

	@Test
	void emptyDecisionMembersPreserveSharedRootCarrierEvenWithAssembledControlInput() {
		var echo = new Echo();
		var choice = new Choose();
		var wrapped = new WrappedChoice();
		var first = Workflows.define("empty-choice")
			.parallel("checks")
			.allSuccessful()
			.branch("choose")
			.decision("pick", choice)
			.when(Route.LEFT)
			.when(Route.RIGHT)
			.end()
			.branch("echo")
			.then(echo)
			.end()
			.terminate(SUCCEEDED)
			.build();
		assertThat(first.definition().output()).isEqualTo(new ListType(Text.class));
		var second = Workflows.define("wrapped-choice")
			.parallel("checks")
			.allSuccessful()
			.branch("echo")
			.then(echo)
			.branch("choose")
			.decision("pick", wrapped)
			.when(Route.LEFT)
			.when(Route.RIGHT)
			.end()
			.end()
			.terminate(SUCCEEDED)
			.build();
		assertThat(second.definition().input()).isEqualTo(Text.class);
		assertThat(second.definition().output()).isEqualTo(new ListType(Text.class));
	}

	@Test
	void invalidMembersAndStaleHandlesRefuseAtBuildOrConstruction() {
		var echo = new Echo();
		assertThatThrownBy(() -> Workflows.define("empty")
			.parallel("checks")
			.allSuccessful()
			.branch("one")
			.end()
			.terminate(SUCCEEDED)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty branch");
		assertThatThrownBy(() -> Workflows.define("duplicate")
			.parallel("checks")
			.allSuccessful()
			.branch("one")
			.then(echo)
			.branch("one")
			.then(echo)
			.end()
			.terminate(SUCCEEDED)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
		var group = Workflows.define("stale").parallel("checks").allSuccessful();
		var first = group.branch("one").then(echo);
		var second = first.branch("two").then(echo);
		assertThatThrownBy(() -> first.then(echo)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("stale");
		second.end().terminate(SUCCEEDED).build();
		assertThatThrownBy(() -> group.branch("three")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> Workflows.define("terminal-member")
			.parallel("checks")
			.allSuccessful()
			.branch("one")
			.decision("choice", new Choose())
			.when(Route.LEFT)
			.terminate(SUCCEEDED)).isInstanceOf(IllegalStateException.class).hasMessageContaining("join");
	}

	@Test
	void siblingLeakAndAmbiguousHomogeneousRoleRefuseRatherThanGuess() {
		var a = new MakeA();
		var b = new MakeB();
		var finish = new Finish();
		assertThatThrownBy(() -> Workflows.define("leak")
			.parallel("checks")
			.allSuccessful()
			.branch("a")
			.then(a)
			.branch("b")
			.then(finish)
			.end()
			.then(finish)
			.terminate(SUCCEEDED)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unbound").hasMessageContaining("leak").hasMessageContaining("checks").hasMessageContaining("member 'b'").hasMessageContaining("declared workflow/group result");
		assertThatThrownBy(() -> Workflows.define("ambiguous")
			.parallel("checks")
			.allSuccessful()
			.branch("a")
			.then(a)
			.branch("a2")
			.then(a)
			.branch("b")
			.then(b)
			.end()
			.then(finish)
			.terminate(SUCCEEDED)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous").hasMessageContaining("workflow 'ambiguous'").hasMessageContaining("domain types");
	}

}
