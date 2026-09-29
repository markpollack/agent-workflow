package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.Workflows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.TypeRef;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StepDeclarationTest {

	public record Request(String text) {
	}

	static class Batch<T> implements Step<List<T>, List<T>> {

		public List<T> execute(StepContext context, List<T> input) {
			return input;
		}

	}

	static final class Requests extends Batch<Request> {

	}

	@SuppressWarnings("rawtypes")
	static final class Raw implements Step {

		public Object execute(StepContext context, Object input) {
			return input;
		}

	}

	@TempDir
	Path directory;

	@Test
	void concreteInheritedGenericSignatureSuppliesBindingAndRecoveryTypes() {
		var deployment = new TestApplication("typed", "v1", Map.of(), Map.of("requests", new Requests()));
		var selection = io.github.markpollack.workflow.flows.compiler.StepTypes.of(deployment.step("requests").getClass());
		var expected = new TypeRef<List<Request>>() {
		}.type();
		assertThat(selection.input()).isEqualTo(expected);
		assertThat(selection.output()).isEqualTo(expected);
		var workflow = Workflows.define("requests").then("requests", deployment.step("requests")).terminate(Terminal.SUCCEEDED).build();
		String id;
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility())) {
			id = runtime.start(workflow, "one", List.of(new Request("kept"))).runId();
		}
		try (var reopened = DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility())) {
			assertThat(reopened.resume(id, workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(reopened.result(id, workflow)).isEqualTo(List.of(new Request("kept")));
		}
	}

	@Test
	void erasedConstructionRawAndLambdaDeclarationsRefuseRatherThanGuessing() {
		Step<Request, Request> lambda = (context, input) -> input;
		for (Step<?, ?> step : List.of(new Batch<Request>(), new Raw(), lambda)) {
            assertThatThrownBy(() -> StepRegistry.of(Map.of("work",step))).isInstanceOfSatisfying(
                WorkflowRefusal.class, failure -> assertThat(failure.code()).isEqualTo("STEP_CONTRACT"));

		}
	}

}
