package io.github.markpollack.workflow.batch.examples;

import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.batch.examples.CompositeRecoveryExample.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** A workflow bean receives named Step beans; nested definitions need no DI framework. */
class CompositeWorkflowTest {

	@TempDir
	Path directory;

	@Configuration(proxyBeanMethods = false)
	static class Application {

		@Bean
		Fetch fetch(Settings settings) {
			return new Fetch(settings);
		}

		@Bean
		Assess assess(Settings settings) {
			return new Assess(settings);
		}

		@Bean
		BuildIndex buildIndex(Settings settings) {
			return new BuildIndex(settings);
		}

		@Bean
		Report report(Settings settings) {
			return new Report(settings);
		}

		@Bean
		Fetch unused(Settings settings) {
			return new Fetch(settings);
		}

		@Bean
		ValidatedWorkflow workflow(@org.springframework.beans.factory.annotation.Qualifier("fetch") Fetch fetch,
				Assess assess, BuildIndex buildIndex, Report report) {
			var poll = CompositeRecoveryExample.poll(fetch, assess);
			return CompositeRecoveryExample.index(poll, buildIndex, report);
		}

		@Bean
		StepRegistry steps(Map<String, Step<?, ?>> namedSteps) {
			return StepRegistry.of(namedSteps);
		}

		@Bean
		ExecutionCompatibility compatibility(Settings settings) {
			return new ExecutionCompatibility("spring-index", "v1", settings.facts());
		}

	}

	private AnnotationConfigApplicationContext context(Settings settings) {
		var context = new AnnotationConfigApplicationContext();
		context.registerBean(Settings.class, () -> settings);
		context.register(Application.class);
		context.refresh();
		return context;
	}

	@Test
	void separateWorkflowBeanAndFreshContextReuseExactSecondFetch() throws Exception {
		Settings settings = new Settings("primary", directory);
		String id;
		Object original;
		RunSnapshot saved;
		try (var context = context(settings);
				var runtime = DurableWorkflows.open(directory.resolve("runs"), context.getBean(StepRegistry.class),
						context.getBean(ExecutionCompatibility.class))) {
			var workflow = context.getBean(ValidatedWorkflow.class);
			original = context.getBean("fetch");
			assertThat(context.getBean(StepRegistry.class).steps().get("fetch")).isSameAs(original);
			id = runtime.start(workflow, "job", new JobHandle("job-42")).runId();
			// Entry, two poll leaves, return, build, second entry, second fetch.
			for (int i = 0; i < 7; i++)
				runtime.advance(id, workflow);
			saved = runtime.inspect(id);
			assertThat(Files.readAllLines(directory.resolve("fetch.calls"))).hasSize(2);
			assertThat(Files.readAllLines(directory.resolve("assess.calls"))).hasSize(1);
		}
		try (var context = context(settings);
				var runtime = DurableWorkflows.open(directory.resolve("runs"), context.getBean(StepRegistry.class),
						context.getBean(ExecutionCompatibility.class))) {
			assertThat(context.getBean("fetch")).isNotSameAs(original);
			var workflow = context.getBean(ValidatedWorkflow.class);
			var end = runtime.resume(id, workflow);
			assertThat(runtime.result(id, workflow)).isEqualTo(new IndexReport("indexed-job-42", "primary"));
			assertThat(end.scopes()).hasSize(3);
			assertThat(end.returns()).hasSize(2);
			assertThat(end.values()).containsAll(saved.values());
			assertThat(Files.readAllLines(directory.resolve("fetch.calls"))).hasSize(2);
			assertThat(Files.readAllLines(directory.resolve("assess.calls"))).hasSize(2);
			assertThat(Files.readAllLines(directory.resolve("build.calls"))).hasSize(1);
			assertThat(Files.readAllLines(directory.resolve("report.calls"))).hasSize(1);
			var calls = end.invocations();
			var before = calls.get(0);
			var build = calls.get(3);
			var after = calls.get(4);
			var report = calls.get(7);
			assertThat(after.inputValue()).isEqualTo(build.outputValue());
			assertThat(report.inputValue()).isEqualTo(after.outputValue());
			assertThat(after.inputValue()).isNotEqualTo(before.inputValue());
			assertThat(end.values()
				.stream()
				.filter(v -> v.valueId().equals(after.outputValue()))
				.findFirst()
				.orElseThrow()
				.sourceValue())
				.isEqualTo(end.scopes()
					.stream()
					.filter(s -> s.id().equals(after.childScope()))
					.findFirst()
					.orElseThrow()
					.localOutcome()
					.successValue());
		}
	}

}
