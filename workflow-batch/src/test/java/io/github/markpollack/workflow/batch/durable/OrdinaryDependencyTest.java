package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;

class OrdinaryDependencyTest {

	@TempDir
	Path directory;

	public static class WithoutJudge {

		public enum Route {

			LEFT, RIGHT

		}

		public static final class Choose implements Step<String, Route> {

			public Route execute(StepContext context, String input) {
				return Route.RIGHT;
			}

		}

		public static final class Echo implements Step<String, String> {

			public String execute(StepContext context, String input) {
				return input;
			}

		}

		public static void main(String[] args) {
			var choose = new Choose();
			var echo = new Echo();
			var workflow = Workflows.define("plain")
				.decision("choice", choose)
				.when(Route.LEFT)
				.then(echo)
				.when(Route.RIGHT)
				.then(echo)
				.end()
				.terminate(SUCCEEDED)
				.build();
			try (var runtime = DurableWorkflows.open(Path.of(args[0]),
					StepRegistry.of(Map.of("choose", choose, "echo", echo)),
					new ExecutionCompatibility("ordinary", "v1", Map.of()))) {
				String id = runtime.start(workflow, "one", "original").runId();
				runtime.resume(id, workflow);
				if (!runtime.result(id, workflow).equals("original"))
					throw new AssertionError("wrong result");
				System.out.println("ordinary durable decision without native dependencies passed");
			}
		}

	}

	@Test
	void ordinaryExecutionDoesNotRequireOptionalNativeArtifacts() throws Exception {
		String classpath = String.join(java.io.File.pathSeparator,
				Arrays
					.stream(System.getProperty("java.class.path")
						.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
					.filter(entry -> !entry.contains("/agent-judge-"))
					.toList());
		var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
				classpath, WithoutJudge.class.getName(), directory.resolve("runs").toString())
			.redirectErrorStream(true)
			.start();
		try {
			assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
			String output = new String(process.getInputStream().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as(output).isZero();
			assertThat(output).contains("without native dependencies passed");
		}
		finally {
			if (process.isAlive())
				process.destroyForcibly();
		}
	}

}
