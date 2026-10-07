package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.JudgmentStatus;
import static io.github.markpollack.workflow.batch.durable.DecisionExecutionTest.*;
import static io.github.markpollack.workflow.batch.durable.NativeVerdictExecutionTest.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static io.github.markpollack.judge.verdict.Verdict.Conclusion.*;
import static org.assertj.core.api.Assertions.*;

class DecisionAtomicityTest {

	@TempDir
	Path directory;

	@ParameterizedTest
	@CsvSource({ "false,rollback", "true,rollback", "false,cancel", "true,cancel", "false,expire", "true,expire" })
	void acceptanceIsAtomicAndLateEvaluationsCannotEnterArms(boolean nativeVerdict, String action) throws Exception {
		var choose = new Choose(Route.RIGHT);
		var prefix = new Prefix("R");
		var assess = new Assess(JudgmentStatus.ERROR);
		var explain = new Explain();
		var workflow = nativeVerdict ? NativeVerdictExecutionTest.workflow(assess, explain)
				: Workflows.define("ordinary")
					.decision("choice", choose)
					.when(Route.LEFT)
					.then(prefix)
					.terminate(SUCCEEDED)
					.when(Route.RIGHT)
					.then(prefix)
					.terminate(SUCCEEDED)
					.end()
					.build();
		var registry = nativeVerdict ? StepRegistry.of(Map.of("assess", assess, "explain", explain))
				: StepRegistry.of(Map.of("choose", choose, "prefix", prefix));
		var handle = new AtomicReference<DurableWorkflows>();
		BoundaryHooks hook = (point, id) -> {
			if (action.equals("rollback") && point.equals("BEFORE_RESULT_COMMIT"))
				throw new DurableRaceTest.SimulatedCrash();
			if (point.equals("AFTER_HANDLER_RETURN")) {
				if (action.equals("cancel"))
					handle.get().cancel(id, "owner", "stop before acceptance");
				if (action.equals("expire"))
					StoreTestSupport.time(handle.get().inspect(id).deadline().toEpochMilli());
			}
		};
		Path file = directory.resolve(nativeVerdict + action);
		try (var runtime = new DurableWorkflows(file, registry, DecisionExecutionTest.deployment(),
				ExecutionPolicy.DEFAULT, hook)) {
			handle.set(runtime);
			StoreTestSupport.controlledClock(file, 100_000);
			String id = runtime
				.start(workflow, "key", nativeVerdict ? new Subject("s", "original") : new Text("original"))
				.runId();
			if (action.equals("rollback"))
				assertThatThrownBy(() -> runtime.advance(id, workflow))
					.isInstanceOf(DurableRaceTest.SimulatedCrash.class);
			else
				runtime.advance(id, workflow);
			var saved = runtime.inspect(id);
			assertThat(saved.decisions()).isEmpty();
			assertThat(saved.values()).hasSize(1);
			assertThat(saved.events()).noneMatch(event -> event.kind().equals("RESULT_COMMITTED"));
			assertThat(saved.invocations()).hasSize(1);
			assertThat(prefix.calls + explain.calls).isZero();
			if (!action.equals("rollback"))
				assertThat(saved.reason().code())
					.isEqualTo(action.equals("cancel") ? "CANCELLED" : "DEADLINE_EXCEEDED");
		}
	}

	public record RefusedInput(Subject subject, Verdict verdict, Verdict.Conclusion conclusion) {
		public RefusedInput {
			throw new IllegalArgumentException("application input rejected");
		}
	}

	static final class RefusedArm implements Step<RefusedInput, NativeVerdictExecutionTest.Reply> {

		public NativeVerdictExecutionTest.Reply execute(StepContext context, RefusedInput input) {
			throw new AssertionError("arm must not execute");
		}

	}

	@Test
	void armInputFailureRetainsAcceptedAssessmentAndRouteWithoutReplay() {
		Path file = directory.resolve("input-failure");
		String id;
		var assess = new Assess(JudgmentStatus.ERROR);
		var arm = new RefusedArm();
		var workflow = Workflows.define("input-failure")
			.verdict("quality", assess)
			.when(PASS)
			.then(arm)
			.terminate(SUCCEEDED)
			.when(FAIL)
			.then(arm)
			.terminate(SUCCEEDED)
			.when(INCONCLUSIVE)
			.then(arm)
			.terminate(SUCCEEDED)
			.when(NOT_APPLICABLE)
			.then(arm)
			.terminate(SUCCEEDED)
			.end()
			.build();
		var registry = StepRegistry.of(Map.of("assess", assess, "arm", arm));
		try (var runtime = DurableWorkflows.open(file, registry, DecisionExecutionTest.deployment())) {
			id = runtime.start(workflow, "key", new Subject("s", "original")).runId();
			var failed = runtime.advance(id, workflow);
			assertThat(failed.reason().code()).isEqualTo("INPUT_ENCODING_FAILED");
			assertThat(failed.decisions()).hasSize(1);
			assertThat(failed.decisions().getFirst().outcome()).isEqualTo("INCONCLUSIVE");
			assertThat(failed.values()).hasSize(3);
		}
		try (var runtime = DurableWorkflows.open(file, registry, DecisionExecutionTest.deployment())) {
			assertThat(runtime.resume(id, workflow).reason().code()).isEqualTo("INPUT_ENCODING_FAILED");
			assertThat(assess.calls).isEqualTo(1);
		}
	}

	@ParameterizedTest
	@CsvSource({ "false,cancel", "true,cancel", "false,expire", "true,expire" })
	void acceptedDecisionSurvivesRevocationOfPreparedArm(boolean nativeVerdict, String action) throws Exception {
		var choose = new Choose(Route.RIGHT);
		var prefix = new Prefix("R");
		var assess = new Assess(JudgmentStatus.ERROR);
		var explain = new Explain();
		var workflow = nativeVerdict ? NativeVerdictExecutionTest.workflow(assess, explain)
				: Workflows.define("ordinary")
					.decision("choice", choose)
					.when(Route.LEFT)
					.then(prefix)
					.terminate(SUCCEEDED)
					.when(Route.RIGHT)
					.then(prefix)
					.terminate(SUCCEEDED)
					.end()
					.build();
		var registry = nativeVerdict ? StepRegistry.of(Map.of("assess", assess, "explain", explain))
				: StepRegistry.of(Map.of("choose", choose, "prefix", prefix));
		Path file = directory.resolve("prepared-" + nativeVerdict + action);
		String id;
		try (var runtime = DurableWorkflows.open(file, registry, DecisionExecutionTest.deployment())) {
			StoreTestSupport.controlledClock(file, 200_000);
			id = runtime.start(workflow, "key", nativeVerdict ? new Subject("s", "original") : new Text("original"))
				.runId();
			var accepted = runtime.advance(id, workflow);
			assertThat(accepted.invocations().getLast().status()).isEqualTo("PREPARED");
			if (action.equals("cancel"))
				runtime.cancel(id, "owner", "stop prepared arm");
			else
				StoreTestSupport.time(accepted.deadline().toEpochMilli());
			var stopped = runtime.resume(id, workflow);
			assertThat(stopped.reason().code()).isEqualTo(action.equals("cancel") ? "CANCELLED" : "DEADLINE_EXCEEDED");
			assertThat(stopped.decisions()).isEqualTo(accepted.decisions());
			assertThat(stopped.values()).isEqualTo(accepted.values());
			assertThat(stopped.invocations().getLast().status()).isEqualTo("REVOKED");
			assertThat(stopped.invocations().getLast().attempts()).isEmpty();
			assertThat(prefix.calls + explain.calls).isZero();
		}
		try (var runtime = DurableWorkflows.open(file, registry, DecisionExecutionTest.deployment())) {
			assertThat(runtime.resume(id, workflow).invocations().getLast().status()).isEqualTo("REVOKED");
			assertThat(choose.calls + assess.calls).isEqualTo(1);
		}
	}

}
