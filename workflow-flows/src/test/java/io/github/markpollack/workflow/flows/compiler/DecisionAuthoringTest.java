package io.github.markpollack.workflow.flows.compiler;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.markpollack.workflow.flows.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.*;
import static org.assertj.core.api.Assertions.*;

class DecisionAuthoringTest {

	public record Request(String text) {
	}

	public record Left(String text) {
	}

	public record Right(String text) {
	}

	enum Route {

		LEFT, RIGHT

	}

	enum Foreign {

		OTHER

	}

	static final class Choose implements Step<Request, Route> {

		public Route execute(StepContext context, Request input) {
			return Route.LEFT;
		}

	}

	static final class Bool implements Step<Request, Boolean> {

		public Boolean execute(StepContext context, Request input) {
			return true;
		}

	}

	static final class MakeLeft implements Step<Request, Left> {

		public Left execute(StepContext context, Request input) {
			return new Left(input.text());
		}

	}

	static final class MakeRight implements Step<Request, Right> {

		public Right execute(StepContext context, Request input) {
			return new Right(input.text());
		}

	}

	static final class NeedLeft implements Step<Left, Request> {

		public Request execute(StepContext context, Left input) {
			return new Request(input.text());
		}

	}

	@Test
	void rejectsIncompleteDuplicateForeignAndNonEnumChoices() {
		var choose = new Choose();
		assertThatThrownBy(() -> Workflows.define("duplicate")
			.decision("choice", choose)
			.when(Route.LEFT)
			.terminate(SUCCEEDED)
			.when(Route.LEFT)
			.terminate(SUCCEEDED)
			.end()
			.build()).hasMessageContaining("duplicate choice outcome").hasMessageContaining("duplicate").hasMessageContaining("choice").hasMessageContaining("keep exactly one when");
		assertThatThrownBy(() -> Workflows.define("foreign")
			.decision("choice", choose)
			.when(Foreign.OTHER)
			.terminate(SUCCEEDED)
			.end()
			.build()).hasMessageContaining("foreign/null choice outcome").hasMessageContaining("foreign").hasMessageContaining("choice").hasMessageContaining("use one of");
		assertThatThrownBy(() -> Workflows.define("missing")
			.decision("choice", choose)
			.when(Route.LEFT)
			.terminate(SUCCEEDED)
			.end()
			.build()).hasMessageContaining("missing choice outcome").hasMessageContaining("missing").hasMessageContaining("choice").hasMessageContaining("RIGHT").hasMessageContaining("add when");
		assertThatThrownBy(() -> Workflows.define("boolean")
			.decision("choice", new Bool())
			.when(Route.LEFT)
			.terminate(SUCCEEDED)
			.when(Route.RIGHT)
			.terminate(SUCCEEDED)
			.end()
			.build()).hasMessageContaining("concrete enum decision required");
		assertThatThrownBy(() -> Workflows.define("null").decision("choice", choose).when(null))
			.isInstanceOf(NullPointerException.class).hasMessageContaining("null").hasMessageContaining("choice").hasMessageContaining("declared decision enum");
	}

	@Test
	void rejectsDanglingSiblingOnlyAndDisagreeingSuccessfulOutputs() {
		var choose = new Choose();
		assertThatThrownBy(() -> Workflows.define("dangling")
			.decision("choice", choose)
			.when(Route.LEFT)
			.when(Route.RIGHT)
			.end()
			.build()).hasMessageContaining("missing terminal");
		assertThatThrownBy(() -> Workflows.define("siblings")
			.decision("choice", choose)
			.when(Route.LEFT)
			.then(new MakeLeft())
			.when(Route.RIGHT)
			.then(new MakeRight())
			.end()
			.then(new NeedLeft())
			.terminate(SUCCEEDED)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing assignment");
		assertThatThrownBy(() -> Workflows.define("outputs")
			.decision("choice", choose)
			.when(Route.LEFT)
			.then(new MakeLeft())
			.terminate(SUCCEEDED)
			.when(Route.RIGHT)
			.then(new MakeRight())
			.terminate(SUCCEEDED)
			.end()
			.build()).hasMessageContaining("successful output disagreement");
	}

	@Test
	void controlResultDoesNotReplaceIncomingCarrierAndLexicalSelectionsUseSharedCompiler() {
		var choose = new Choose();
		var workflow = Workflows.define("immediate")
			.decision("choice", choose)
			.when(Route.LEFT)
			.terminate(SUCCEEDED)
			.when(Route.RIGHT)
			.terminate(SUCCEEDED)
			.end()
			.build();
		assertThat(workflow.definition().input()).isEqualTo(Request.class);
		assertThat(workflow.definition().output()).isEqualTo(Request.class);
		var programmatic = ValidatedWorkflow.compile(new WorkflowModel.Definition<>("immediate", Request.class,
				Request.class, workflow.definition().nodes(), null), List.of(choose), DeadlinePolicy.DEFAULT);
		assertThat(programmatic.authoredIdentity()).isEqualTo(workflow.authoredIdentity());
		var block = Workflows.define("unfinished").decision("choice", choose);
		var left = block.when(Route.LEFT);
		left.decision("nested", choose);
		assertThatThrownBy(() -> left.when(Route.RIGHT)).hasMessageContaining("unfinished");
		assertThatThrownBy(block::end).hasMessageContaining("unfinished");
	}

}
