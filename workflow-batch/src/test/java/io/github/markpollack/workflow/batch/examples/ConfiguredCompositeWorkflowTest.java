package io.github.markpollack.workflow.batch.examples;

import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.batch.examples.CompositeRecoveryExample.*;
import io.github.markpollack.workflow.batch.examples.ConfiguredCompositeExample.Services;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Same-type bean selection is explicit; Spring supplies one registry map automatically.
 */
class ConfiguredCompositeWorkflowTest {

	@TempDir
	Path directory;

	@Configuration(proxyBeanMethods = false)
	static class Application {

		@Bean("fetch-fast")
		Fetch fast(Services settings) {
			return new Fetch(settings.fastSettings());
		}

		@Bean("fetch-careful")
		Fetch careful(Services settings) {
			return new Fetch(settings.carefulSettings());
		}

		@Bean
		Assess assess(Services settings) {
			return new Assess(settings.sharedSettings());
		}

		@Bean
		BuildIndex build(Services settings) {
			return new BuildIndex(settings.sharedSettings());
		}

		@Bean
		Report report(Services settings) {
			return new Report(settings.sharedSettings());
		}

		@Bean
		ValidatedWorkflow workflow(@Qualifier("fetch-fast") Fetch fast, @Qualifier("fetch-careful") Fetch careful,
				Assess assess, BuildIndex build, Report report) {
			return ConfiguredCompositeExample.workflow(fast, careful, assess, build, report);
		}

		@Bean
		StepRegistry steps(Map<String, Step<?, ?>> beans) {
			return StepRegistry.of(beans);
		}

		@Bean
		ExecutionCompatibility compatibility(Services settings) {
			return new ExecutionCompatibility("configured-index", "v1", settings.facts());
		}

	}

	private AnnotationConfigApplicationContext context(Services settings) {
		var context = new AnnotationConfigApplicationContext();
		context.registerBean(Services.class, () -> settings);
		context.register(Application.class);
		context.refresh();
		return context;
	}

	@Test
	void configuredSameAuthoredPollsRemainDistinctThroughNestedFreshSpringObjects() throws Exception {
		var settings = new Services("fast-service", "careful-service", directory);
		String id;
		RunSnapshot saved;
		Object original;
		try (var context = context(settings);
				var runtime = DurableWorkflows.open(directory.resolve("runs"), context.getBean(StepRegistry.class),
						context.getBean(ExecutionCompatibility.class))) {
			original = context.getBean("fetch-fast");
			var workflow = context.getBean(ValidatedWorkflow.class);
			id = runtime.start(workflow, "job", new JobHandle("job-42")).runId();
			for (int i = 0; i < 4; i++)
				runtime.advance(id, workflow);
			saved = runtime.inspect(id);
			assertThat(saved.definitions()).hasSize(5);
			var polls = saved.definitions().values().stream().filter(d -> d.leaves().containsValue("assess")).toList();
			assertThat(polls).hasSize(2);
			assertThat(polls.get(0).authoredIdentity()).isEqualTo(polls.get(1).authoredIdentity());
			assertThat(polls.stream().flatMap(d -> d.leaves().values().stream())).contains("fetch-fast",
					"fetch-careful");
			var local = saved.scopes().stream().filter(s -> s.depth() == 2).findFirst().orElseThrow();
			var value = saved.values()
				.stream()
				.filter(v -> v.valueId().equals(local.localOutcome().successValue()))
				.findFirst()
				.orElseThrow();
			assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readValue(value.payload(), JobStatus.class))
				.isEqualTo(new JobStatus("job-42", "fast-service"));
		}
		try (var context = context(settings);
				var runtime = DurableWorkflows.open(directory.resolve("runs"), context.getBean(StepRegistry.class),
						context.getBean(ExecutionCompatibility.class))) {
			assertThat(context.getBean("fetch-fast")).isNotSameAs(original);
			var workflow = context.getBean(ValidatedWorkflow.class);
			var end = runtime.resume(id, workflow);
			assertThat(end.definitions()).isEqualTo(saved.definitions());
			assertThat(end.scopes()).hasSize(5);
			assertThat(end.returns()).hasSize(4);
			assertThat(end.values()).containsAll(saved.values());
			assertThat(runtime.result(id, workflow)).isEqualTo(new IndexReport("indexed-job-42", "careful-service"));
			assertThat(Files.readAllLines(directory.resolve("fast/fetch.calls"))).hasSize(1);
			assertThat(Files.readAllLines(directory.resolve("careful/fetch.calls"))).hasSize(1);
			assertThat(Files.readAllLines(directory.resolve("shared/assess.calls"))).hasSize(2);
		}
	}

}
