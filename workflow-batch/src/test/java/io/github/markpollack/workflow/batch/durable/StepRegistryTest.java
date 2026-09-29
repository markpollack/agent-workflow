package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.Proxy;
import java.util.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.StepTypes;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StepRegistryTest {

	public interface TextStep extends Step<String, String> {

	}

	public interface SameTextStep extends Step<String, String> {

	}

	public interface ListStep extends Step<String, List<String>> {

	}

	private static Step<?, ?> proxy(Class<?>... interfaces) {
		return (Step<?, ?>) Proxy.newProxyInstance(StepRegistryTest.class.getClassLoader(), interfaces,
				(instance, method, args) -> {
					throw new AssertionError("no invocation during validation");
				});
	}

	@Test
	void conflictingGenericProxyContractsRefuseInBothInterfaceOrders() {
		for (Class<?>[] order : List.of(new Class<?>[] { TextStep.class, ListStep.class },
				new Class<?>[] { ListStep.class, TextStep.class })) {
			var step = proxy(order);
			assertThatThrownBy(() -> StepTypes.of(step.getClass())).hasMessageContaining("conflicting Step contracts");
			assertThatThrownBy(() -> StepRegistry.of(Map.of("step", step))).isInstanceOfSatisfying(
					WorkflowRefusal.class, failure -> assertThat(failure.code()).isEqualTo("STEP_CONTRACT"));
			assertThatThrownBy(() -> Workflows.define("conflict").then(step))
				.hasMessageContaining("conflicting Step contracts");
		}
		var agreeing = proxy(TextStep.class, SameTextStep.class);
		assertThat(StepTypes.of(agreeing.getClass()).input()).isEqualTo(String.class);
		assertThat(StepRegistry.of(Map.of("step", agreeing)).steps().get("step")).isSameAs(agreeing);
	}

	@Test
	void duplicateNamesAliasesAndMapMutationCannotChangeFrozenSelections() {
		var first = proxy(TextStep.class);
		var second = proxy(TextStep.class);
		assertThatThrownBy(() -> StepRegistry.builder().register("same", first).register("same", second))
			.isInstanceOfSatisfying(WorkflowRefusal.class, ex -> assertThat(ex.code()).isEqualTo("STEP_DUPLICATE"));
		assertThatThrownBy(() -> StepRegistry.of(Map.of("one", first, "alias", first)))
			.isInstanceOfSatisfying(WorkflowRefusal.class, ex -> assertThat(ex.code()).isEqualTo("STEP_ALIAS"));
		var source = new HashMap<String, Step<?, ?>>();
		source.put("first", first);
		source.put("second", second);
		var registry = StepRegistry.of(source);
		source.clear();
		assertThat(registry.steps()).hasSize(2);
		assertThat(registry.nameOf(first)).isEqualTo("first");
		assertThatThrownBy(() -> registry.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> registry.nameOf(proxy(TextStep.class))).isInstanceOf(WorkflowRefusal.class);
		var workflow = Workflows.define("reuse")
			.then(first)
			.then(second)
			.then(first)
			.terminate(Terminal.SUCCEEDED)
			.build();
		assertThat(workflow.graph().bindings()).hasSize(3);
		assertThat(new ResolvedApplication(registry, new ExecutionCompatibility("app", "v1", Map.of()), workflow)
			.selections()).hasSize(3).containsValues("first", "second");
	}

}
