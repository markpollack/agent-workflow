package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.CompositeExecutionTest.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class CompositeContinuationTest {

	@TempDir
	Path directory;

	@Test
	void acceptedAuthoredOutcomesSurviveLocalDeadlineAcrossReopen() throws Exception {
		for (Terminal terminal : Terminal.values()) {
			var calls = new ArrayList<String>();
			var step = new Prefix("F", calls);
			var child = Workflows.define("child")
				.maxDuration(Duration.ofMillis(100))
				.then(step)
				.terminate(terminal, "business result")
				.build();
			var workflow = terminal == Terminal.SUCCEEDED ? wrapper(child)
					: ValidatedWorkflow.compile(
							new Definition<>("parent", Text.class, Text.class, List.of(new Child("call", child)), null),
							Map.of());
			var registry = StepRegistry.of(Map.of("fast", step));
			var file = directory.resolve(terminal.name());
			String id;
			RunSnapshot.Scope accepted;
			try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
				controlledClock(file, 10_000);
				id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				time(10_099);
				accepted = runtime.advance(id, workflow)
					.scopes()
					.stream()
					.filter(s -> s.depth() == 1)
					.findFirst()
					.orElseThrow();
				assertThat(accepted.localOutcome().status()).isEqualTo(terminal.name());
			}
			time(10_101);
			try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
				for (int i = 0; i < 2; i++) {
					var pending = runtime.inspect(id);
					assertThat(pending.scopes().stream().filter(s -> s.depth() == 1).findFirst().orElseThrow())
						.isEqualTo(accepted);
					assertThat(runtime.discover()).extracting(RunSnapshot::runId).containsExactly(id);
				}
				var end = runtime.resume(id, workflow);
				assertThat(end.status()).isEqualTo(
						terminal == Terminal.SUCCEEDED ? RunSnapshot.Status.SUCCEEDED : RunSnapshot.Status.FAILED);
				assertThat(end.scopes().stream().filter(s -> s.depth() == 1).findFirst().orElseThrow().localOutcome())
					.isEqualTo(accepted.localOutcome());
				assertThat(end.returns()).hasSize(1);
				assertThat(runtime.inspect(id)).isEqualTo(end);
				assertThat(calls).containsExactly("F:x");
			}
		}
	}

	@Test
	void committedReturnIsReusedButAncestorStillFencesNextLeaf() throws Exception {
		for (String disposition : List.of("live", "expire", "cancel")) {
			var calls = new ArrayList<String>();
			var inner = new Prefix("I", calls);
			var next = new Prefix("N", calls);
			var child = Workflows.define("child").then(inner).terminate(Terminal.SUCCEEDED).build();
			var workflow = Workflows.define("parent")
				.maxDuration(Duration.ofMillis(200))
				.subWorkflow("child", child)
				.then(next)
				.terminate(Terminal.SUCCEEDED)
				.build();
			var file = directory.resolve("returned-" + disposition);
			var registry = StepRegistry.of(Map.of("inner", inner, "next", next));
			String id;
			RunSnapshot saved;
			try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
				controlledClock(file, 10_000);
				id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				runtime.advance(id, workflow);
				saved = runtime.advance(id, workflow);
				assertThat(saved.returns()).hasSize(1);
				assertThat(saved.status()).isEqualTo(RunSnapshot.Status.ACTIVE);
			}
			try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
				if (disposition.equals("expire"))
					time(10_200);
				if (disposition.equals("cancel"))
					runtime.cancel(id, "owner", "stop");
				var end = runtime.resume(id, workflow);
				assertThat(end.returns()).isEqualTo(saved.returns());
				assertThat(end.scopes().stream().filter(s -> s.depth() == 1).findFirst().orElseThrow())
					.isEqualTo(saved.scopes().stream().filter(s -> s.depth() == 1).findFirst().orElseThrow());
				assertThat(end.values()).containsAll(saved.values());
				assertThat(calls)
					.containsExactlyElementsOf(disposition.equals("live") ? List.of("I:x", "N:Ix") : List.of("I:x"));
				assertThat(end.status()).isEqualTo(switch (disposition) {
					case "live" -> RunSnapshot.Status.SUCCEEDED;
					case "expire" -> RunSnapshot.Status.FAILED;
					default -> RunSnapshot.Status.CANCELLED;
				});
			}
		}
	}

	@Test
	void cancellationActorCannotImpersonateAnAuthoredTerminal() {
		for (String actor : List.of("workflow", "composite", "runtime", "store")) {
			var step = new Prefix("F", new ArrayList<>());
			var workflow = wrapper(poll(step));
			try (var runtime = DurableWorkflows.open(directory.resolve("actor-" + actor),
					StepRegistry.of(Map.of("fast", step)), compatibility())) {
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				var cancelled = runtime.cancel(id, actor, "operator stop");
				assertThat(cancelled.status()).isEqualTo(RunSnapshot.Status.CANCELLED);
				assertThat(cancelled.reason().actor()).isEqualTo(actor);
				assertThat(runtime.resume(id, workflow)).isEqualTo(cancelled);
			}
		}
	}

	static final class InterruptOnce implements Step<Text, Text> {

		int calls;

		public Text execute(StepContext context, Text input) {
			calls++;
			Thread.currentThread().interrupt();
			return input;
		}

	}

	@Test
	void interruptedNestedResumeReturnsActiveOutcomeThenFreshOwnerReturnsItOnce() {
		var step = new InterruptOnce();
		var child = Workflows.define("inner").then(step).terminate(Terminal.SUCCEEDED).build();
		var workflow = wrapper(wrapper(child));
		var file = directory.resolve("interrupted");
		var registry = StepRegistry.of(Map.of("interrupt", step));
		String id;
		RunSnapshot saved;
		try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
			id = runtime.start(workflow, "one", new Text("x")).runId();
			try {
				saved = runtime.resume(id, workflow);
				assertThat(Thread.currentThread().isInterrupted()).isTrue();
				assertThat(saved.status()).isEqualTo(RunSnapshot.Status.ACTIVE);
				assertThat(saved.returns()).isEmpty();
				assertThat(saved.scopes().stream().filter(s -> s.depth() == 2).findFirst().orElseThrow().lifecycle())
					.isEqualTo("LOCAL_TERMINAL");
			}
			finally {
				Thread.interrupted();
			}
		}
		try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
			var oneReturn = runtime.advance(id, workflow);
			assertThat(oneReturn.status()).isEqualTo(RunSnapshot.Status.ACTIVE);
			assertThat(oneReturn.returns()).hasSize(1);
			assertThat(oneReturn.scopes()
				.stream()
				.filter(s -> s.depth() == 1)
				.findFirst()
				.orElseThrow()
				.localOutcome()
				.status()).isEqualTo("SUCCEEDED");
			var end = runtime.resume(id, workflow);
			assertThat(end.returns()).hasSize(2);
			assertThat(end.values()).containsAll(saved.values());
			assertThat(end.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(step.calls).isEqualTo(1);
		}
	}

	@Test
	void nestedShutdownDrainsWholeResumeBeforeReleasingOwnership() throws Exception {
		var step = new CompositeRaceTest.Block();
		var workflow = wrapper(wrapper(Workflows.define("inner").then(step).terminate(Terminal.SUCCEEDED).build()));
		var file = directory.resolve("drain");
		var registry = StepRegistry.of(Map.of("block", step));
		var runtime = DurableWorkflows.open(file, registry, compatibility());
		String id = runtime.start(workflow, "one", new Text("x")).runId();
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			var caller = threads.submit(() -> runtime.resume(id, workflow));
			try {
				assertThat(step.entered.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(runtime.shutdown(Duration.ZERO)).isFalse();
				assertThatThrownBy(() -> DurableWorkflows.open(file)).isInstanceOfSatisfying(WorkflowRefusal.class,
						e -> assertThat(e.code()).isEqualTo("OWNER_ACTIVE"));
				assertThatThrownBy(runtime::discover).isInstanceOfSatisfying(WorkflowRefusal.class,
						e -> assertThat(e.code()).isEqualTo("RUNTIME_CLOSED"));
				step.release.countDown();
				assertThat(caller.get(10, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			}
			finally {
				step.release.countDown();
				runtime.close();
			}
		}
		try (var runtime2 = DurableWorkflows.open(file, registry, compatibility())) {
			assertThat(runtime2.inspect(id).returns()).hasSize(2);
			assertThat(runtime2.result(id, workflow)).isEqualTo(new Text("x"));
		}
	}

	@Test
	void compactDagAtDefaultWorkLimitCountsAllEdgesAndNextCallRefusesBeforeAdmission() {
		var step = new Prefix("F", new ArrayList<>());
		var blocks = new ArrayList<ValidatedWorkflow>();
		var costs = new ArrayList<Long>();
		var block = Workflows.define("leaf").then(step).terminate(Terminal.SUCCEEDED).build();
		long logical = 1;
		while (logical + 1 <= 10_000) {
			blocks.add(block);
			costs.add(logical + 1);
			block = Workflows.define("double-" + blocks.size())
				.subWorkflow("one", block)
				.subWorkflow("two", block)
				.terminate(Terminal.SUCCEEDED)
				.build();
			logical = 2 * (logical + 1);
		}
		// Each block costs its own logical calls plus the calling placement.
		Workflows.Sequence sequence = Workflows.define("at-limit").then("initial", step);
		long remaining = 9_999;
		int calls = 0;
		for (int i = blocks.size() - 1; i >= 0; i--) {
			while (costs.get(i) <= remaining) {
				sequence = sequence.subWorkflow("call-" + calls++, blocks.get(i));
				remaining -= costs.get(i);
			}
		}
		if (remaining == 1)
			sequence = sequence.then("last", step);
		var at = sequence.terminate(Terminal.SUCCEEDED).build();
		var registry = StepRegistry.of(Map.of("fast", step));
		var selection = new WorkflowExecutionBindings(registry, compatibility(), at);
		assertThat(selection.bounds().logical()).isEqualTo(10_000);
		assertThat(selection.bounds().scopes()).isEqualTo(selection.bounds().composites() + 1);
		assertThat(selection.bounds().attempts()).isEqualTo(selection.bounds().leaves() * 3);
		assertThat(selection.descriptors()).hasSizeLessThan(16);
		var above = wrapper(at);
		try (var runtime = DurableWorkflows.open(directory.resolve("over-limit"), registry, compatibility())) {
			assertThatThrownBy(() -> runtime.start(above, "one", new Text("x"))).isInstanceOfSatisfying(
					WorkflowRefusal.class, e -> assertThat(e.code()).isEqualTo("COMPOSITION_LIMIT"));
			assertThat(runtime.discover()).isEmpty();
		}
	}

}
