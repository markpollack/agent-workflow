package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.batch.examples.ParallelAssessmentExample;
import io.github.markpollack.workflow.batch.examples.ParallelAssessmentExample.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;

class ParallelExecutionTest {

	@TempDir
	Path directory;

	static ExecutionCompatibility deployment() {
		return new ExecutionCompatibility("parallel-tests", "1", Map.of());
	}

	static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS))
				throw new IllegalStateException("coordination timed out");
		}
		catch (InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static class Quality extends AssessQuality {

		final CountDownLatch entered, release;

		int calls;

		Quality(CountDownLatch entered, CountDownLatch release) {
			this.entered = entered;
			this.release = release;
		}

		public QualityAssessment execute(StepContext c, Revision input) {
			calls++;
			entered.countDown();
			await(release);
			return super.execute(c, input);
		}

	}

	static class Backport extends AssessBackport {

		final CountDownLatch entered, release;

		int calls;

		Backport(CountDownLatch entered, CountDownLatch release) {
			this.entered = entered;
			this.release = release;
		}

		public BackportAssessment execute(StepContext c, BackportInput input) {
			calls++;
			entered.countDown();
			await(release);
			return super.execute(c, input);
		}

	}

	@Test
	void concurrentAssessmentsRetainTypedInputsAndNegativeBusinessValue() throws Exception {
		var entered = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var fetch = new FetchRevision();
		var quality = new Quality(entered, release);
		var backport = new Backport(entered, release);
		var report = new WriteReport();
		var workflow = ParallelAssessmentExample.workflow(fetch, quality, backport, report);
		var registry = StepRegistry
			.of(Map.of("fetch", fetch, "quality", quality, "backport", backport, "report", report));
		try (var runtime = DurableWorkflows.open(directory.resolve("concurrent"), registry, deployment(),
				new ExecutionPolicy(3, 32, 10000, 2))) {
			var run = runtime.start(workflow, "request", new PullRequest("42"));
			try (var callers = Executors.newSingleThreadExecutor()) {
				var future = callers.submit(() -> runtime.resume(run.runId(), workflow));
				try {
					assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
					assertThatThrownBy(() -> runtime.resume(run.runId(), workflow)).isInstanceOf(WorkflowRefusal.class)
						.hasMessageContaining("already executing");
					assertThat(runtime.inspect(run.runId()).invocations())
						.filteredOn(i -> i.status().equals("UNRESOLVED"))
						.hasSize(2);
				}
				finally {
					release.countDown();
				}
				assertThat(future.get(20, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			}
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Report("42:quality=false:backport=true"));
			assertThat(quality.calls).isEqualTo(1);
			assertThat(backport.calls).isEqualTo(1);
			var snapshot = runtime.inspect(run.runId());
			var backportCall = snapshot.invocations()
				.stream()
				.filter(i -> i.placement().contains("backport"))
				.findFirst()
				.orElseThrow();
			var saved = snapshot.values()
				.stream()
				.filter(v -> v.valueId().equals(backportCall.inputValue()))
				.findFirst()
				.orElseThrow();
			assertThat(new TypeContracts().decodeBytes(saved.payload(), BackportInput.class))
				.isEqualTo(new BackportInput(new PullRequest("42"), new Revision("sha-42")));
		}
	}

	public record Text(String value) {
	}

	public record Done(String value) {
	}

	public static class Echo implements Step<Text, Text> {

		final String prefix;

		int calls;

		Echo(String prefix) {
			this.prefix = prefix;
		}

		public Text execute(StepContext c, Text input) {
			calls++;
			return new Text(prefix + input.value());
		}

	}

	public static class Aggregate implements Step<List<Text>, Done> {

		public Done execute(StepContext c, List<Text> input) {
			return new Done(input.stream().map(Text::value).reduce("", String::concat));
		}

	}

	public static class Fail implements Step<Text, Text> {

		public Text execute(StepContext c, Text input) {
			throw new IllegalStateException("assessment execution failed");
		}

	}

	@Test
	void failureSettlesQueuedSiblingsAtCapacityOneAndDoesNotRunJoin() {
		var fail = new Fail();
		var second = new Echo("B");
		var third = new Echo("C");
		var aggregate = new Aggregate();
		var workflow = Workflows.define("settlement")
			.parallel("checks")
			.allSuccessful()
			.branch("failed")
			.then(fail)
			.branch("second")
			.then(second)
			.branch("third")
			.then(third)
			.end()
			.then(aggregate)
			.terminate(SUCCEEDED)
			.build();
		try (var runtime = DurableWorkflows.open(directory.resolve("failure"),
				StepRegistry.of(Map.of("fail", fail, "second", second, "third", third, "aggregate", aggregate)),
				deployment(), new ExecutionPolicy(3, 32, 10000, 1))) {
			var run = runtime.start(workflow, "failure", new Text("x"));
			var result = runtime.resume(run.runId(), workflow);
			assertThat(result.status()).isEqualTo(RunSnapshot.Status.FAILED);
			assertThat(result.reason().code()).isEqualTo("PARALLEL_FAILED");
			assertThat(second.calls).isEqualTo(1);
			assertThat(third.calls).isEqualTo(1);
			assertThat(result.invocations()).hasSize(3);
			assertThat(result.scopes())
				.filteredOn(s -> s.localOutcome() != null && s.localOutcome().status().equals("SUCCEEDED"))
				.hasSize(2);
		}
	}

	@Test
	void nestedGroupsAndCompositesMakeProgressAtCapacityOne() {
		var a = new Echo("A");
		var b = new Echo("B");
		var c = new Echo("C");
		var aggregate = new Aggregate();
		var inner = Workflows.define("inner")
			.parallel("inner-checks")
			.allSuccessful()
			.branch("a")
			.then(a)
			.branch("b")
			.then(b)
			.end()
			.then(aggregate)
			.terminate(SUCCEEDED)
			.build();
		var workflow = Workflows.define("outer")
			.parallel("outer-checks")
			.allSuccessful()
			.branch("composite")
			.subWorkflow("inner", inner)
			.branch("direct-nested")
			.parallel("direct")
			.allSuccessful()
			.branch("b")
			.then(b)
			.branch("c")
			.then(c)
			.end()
			.then(aggregate)
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var runtime = DurableWorkflows.open(directory.resolve("nested"),
				StepRegistry.of(Map.of("a", a, "b", b, "c", c, "aggregate", aggregate)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 1))) {
			var run = runtime.start(workflow, "nested", new Text("x"));
			assertThat(runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(List.of(new Done("AxBx"), new Done("BxCx")));
		}
	}

	enum Route {

		LEFT, RIGHT

	}

	public static class Choose implements Step<Text, Route> {

		public Route execute(StepContext c, Text t) {
			return Route.LEFT;
		}

	}

	public record A(String value) {
	}

	public record B(String value) {
	}

	public static class MakeA implements Step<Text, A> {

		public A execute(StepContext c, Text t) {
			return new A(t.value());
		}

	}

	public static class MakeB implements Step<Text, B> {

		public B execute(StepContext c, Text t) {
			return new B(t.value());
		}

	}

	public static class Finalize implements Step<Done, Text> {

		public Text execute(StepContext c, Done d) {
			return new Text(d.value());
		}

	}

	@Test
	void choicesInsideMembersAndGroupsInsideChoicesShareRuntime() {
		var choose = new Choose();
		var a = new Echo("A");
		var b = new Echo("B");
		var aggregate = new Aggregate();
		var workflow = Workflows.define("choices")
			.decision("outer", choose)
			.when(Route.LEFT)
			.parallel("left-group")
			.allSuccessful()
			.branch("choice")
			.decision("inner", choose)
			.when(Route.LEFT)
			.then(a)
			.when(Route.RIGHT)
			.then(b)
			.end()
			.branch("plain")
			.then(b)
			.end()
			.then(aggregate)
			.when(Route.RIGHT)
			.parallel("right-group")
			.allSuccessful()
			.branch("a")
			.then(a)
			.branch("b")
			.then(b)
			.end()
			.then(aggregate)
			.end()
			.terminate(SUCCEEDED)
			.build();
		try (var runtime = DurableWorkflows.open(directory.resolve("choice"),
				StepRegistry.of(Map.of("choose", choose, "a", a, "b", b, "aggregate", aggregate)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 1))) {
			var run = runtime.start(workflow, "choice", new Text("x"));
			assertThat(runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Done("AxBx"));
		}
	}

	@Test
	void nestedHeterogeneousStructuralProductsRetainMemberResultsWithoutInventedCodec() {
		var a = new MakeA();
		var b = new MakeB();
		var aggregate = new Aggregate();
		var echo = new Echo("C");
		var finish = new Finalize();
		var workflow = Workflows.define("nested-products")
			.parallel("outer")
			.allSuccessful()
			.branch("nested")
			.parallel("inner")
			.allSuccessful()
			.branch("a")
			.then(a)
			.branch("b")
			.then(b)
			.end()
			.branch("direct")
			.then(echo)
			.parallel("homogeneous")
			.allSuccessful()
			.branch("one")
			.then(echo)
			.branch("two")
			.then(echo)
			.end()
			.then(aggregate)
			.end()
			.then(finish)
			.terminate(SUCCEEDED)
			.build();
		try (var runtime = DurableWorkflows.open(directory.resolve("structural"),
				StepRegistry.of(Map.of("a", a, "b", b, "echo", echo, "aggregate", aggregate, "finish", finish)),
				deployment(), new ExecutionPolicy(3, 32, 10000, 1))) {
			var run = runtime.start(workflow, "structural", new Text("x"));
			var result = runtime.resume(run.runId(), workflow);
			assertThat(result.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Text("CCxCCx"));
			assertThat(result.groups()).hasSize(3).allMatch(g -> g.phase().equals("SETTLED"));
			assertThat(result.values()).anyMatch(v -> v.declaredType().equals(A.class.getTypeName()))
				.anyMatch(v -> v.declaredType().equals(B.class.getTypeName()));
		}
	}

	@Test
	void homogeneousResultsRemainInDeclarationOrderWhenSecondBranchCommitsFirst() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var first = new ParallelLifecycleTest.Blocking(entered, release);
		var second = new Echo("second");
		var aggregate = new Aggregate();
		var workflow = Workflows.define("ordered")
			.parallel("checks")
			.allSuccessful()
			.branch("first")
			.then(first)
			.branch("second")
			.then(second)
			.end()
			.then(aggregate)
			.terminate(SUCCEEDED)
			.build();
		var file = directory.resolve("ordered");
		BoundaryHooks hooks = (point, id) -> {
			if (point.equals("AFTER_RESULT_COMMIT")) {
				try {
					var state = new com.fasterxml.jackson.databind.ObjectMapper()
						.readTree(StoreTestSupport.state(file, id));
					for (var call : state.path("invocations"))
						if (call.path("placement").asText().contains("second")
								&& call.path("outcome").asText().equals("COMMITTED"))
							release.countDown();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
		};
		try (var runtime = new DurableWorkflows(file,
				StepRegistry.of(Map.of("first", first, "second", second, "aggregate", aggregate)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 2), hooks)) {
			var run = runtime.start(workflow, "ordered", new Text("x"));
			var end = runtime.resume(run.runId(), workflow);
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Done("blockxsecondx"));
			var committed = end.events().stream().filter(e -> e.kind().equals("RESULT_COMMITTED")).toList();
			var secondCall = end.invocations()
				.stream()
				.filter(i -> i.placement().contains("second"))
				.findFirst()
				.orElseThrow();
			assertThat(committed.getFirst().detail()).startsWith(secondCall.invocationId());
			assertThat(end.groups().getFirst().members().stream().map(m -> m.scope()).toList())
				.containsExactly(end.scopes().get(1).id(), end.scopes().get(2).id());
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void choiceArmsCanCaptureHeterogeneousStructuralProductsAndRecover() {
		var choose = new Choose();
		var a = new MakeA();
		var b = new MakeB();
		var echo = new Echo("after");
		var workflow = Workflows.define("structural-choice")
			.decision("route", choose)
			.when(Route.LEFT)
			.parallel("left")
			.allSuccessful()
			.branch("a")
			.then(a)
			.branch("b")
			.then(b)
			.end()
			.when(Route.RIGHT)
			.parallel("right")
			.allSuccessful()
			.branch("a")
			.then(a)
			.branch("b")
			.then(b)
			.end()
			.end()
			.then(echo)
			.terminate(SUCCEEDED)
			.build();
		var file = directory.resolve("structural-choice");
		String id;
		var registry = StepRegistry.of(Map.of("choose", choose, "a", a, "b", b, "echo", echo));
		try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
			id = runtime.start(workflow, "choice", new Text("x")).runId();
			while (runtime.inspect(id).groups().isEmpty()
					|| !runtime.inspect(id).groups().getFirst().phase().equals("SETTLED"))
				runtime.advance(id, workflow);
			assertThat(echo.calls).isZero();
		}
		String accepted = readState(file, id);
		try {
			StoreTestSupport.mutate(file, id, state -> {
				var decision = state.path("decisions").elements().next();
				var captures = (com.fasterxml.jackson.databind.node.ObjectNode) decision.path("captures");
				captures.put(captures.fieldNames().next(),
						state.path("scopes").path(state.path("rootScope").asText()).path("input").asText());
			});
			try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
				assertThatThrownBy(() -> runtime.resume(id, workflow)).isInstanceOf(WorkflowRefusal.class)
					.hasMessageContaining("structural capture source differs");
				assertThat(echo.calls).isZero();
			}
			StoreTestSupport.mutate(file, id,
					state -> ((com.fasterxml.jackson.databind.node.ObjectNode) state).set("decisions",
							new com.fasterxml.jackson.databind.ObjectMapper()
								.valueToTree(parseState(accepted).get("decisions"))));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		try (var runtime = DurableWorkflows.open(file, registry, deployment())) {
			assertThat(runtime.resume(id, workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(id, workflow)).isEqualTo(new Text("afterx"));
			assertThat(runtime.inspect(id).decisions().getFirst().captures()).isNotEmpty();
		}
	}

	private static String readState(Path file, String id) {
		try {
			return StoreTestSupport.state(file, id);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static com.fasterxml.jackson.databind.JsonNode parseState(String json) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
