package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

public final class KernelFixtures {

	public record Request(String text) {
	}

	public record First(String text) {
	}

	public record SecondInput(First first, Request original) {
	}

	public record Second(String text) {
	}

	public record Third(String text) {
	}

	public record Fourth(First laterFirst, Request laterRequest, String text) {
	}

	public record FifthInput(Fourth latest, SecondInput earlier) {
	}

	public record ChangedFifthInput(First latest, SecondInput earlier) {
	}

	public record Reply(String text) {
	}

	public record Nested<T>(T item, List<T> items) {
	}

	public static class FirstOperation implements Step<Request, First> {

		public First execute(StepContext context, Request input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "first", context, input.text());
			return new First("first:" + input.text());
		}

	}

	public static class SecondOperation implements Step<SecondInput, Second> {

		public Second execute(StepContext context, SecondInput input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "second", context, input.first().text() + "/" + input.original().text());
			return new Second("second:" + input.first().text() + "/" + input.original().text());
		}

	}

	public static class ThirdOperation implements Step<Second, Third> {

		public Third execute(StepContext context, Second input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "third", context, input.text());
			return new Third(input.text());
		}

	}

	public static class FourthOperation implements Step<Third, Fourth> {

		public Fourth execute(StepContext context, Third input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "fourth", context, input.text());
			return new Fourth(new First("changed-first"), new Request("changed-request"), input.text());
		}

	}

	public static class FifthOperation implements Step<FifthInput, Reply> {

		public Reply execute(StepContext context, FifthInput input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "fifth", context,
					input.earlier().first().text() + "/" + input.earlier().original().text());
			return new Reply(input.earlier().first().text() + "/" + input.earlier().original().text() + "/"
					+ input.latest().laterFirst().text());
		}

	}

	public static class ChangedThird implements Step<Second, Request> {

		public Request execute(StepContext context, Second input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "third", context, input.text());
			return new Request("changed-request");
		}

	}

	public static class ChangedFourth implements Step<Request, First> {

		public First execute(StepContext context, Request input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "fourth", context, input.text());
			return new First("changed-first");
		}

	}

	public static class ChangedFifth implements Step<ChangedFifthInput, Reply> {

		public Reply execute(StepContext context, ChangedFifthInput input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "fifth", context,
					input.earlier().first().text() + "/" + input.earlier().original().text());
			return new Reply(input.earlier().first().text() + "/" + input.earlier().original().text() + "/"
					+ input.latest().text());
		}

	}

	public static class Echo implements Step<Request, Request> {

		public Request execute(StepContext context, Request input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "echo", context, input.text());
			return new Request(input.text() + config.getOrDefault("suffix", ""));
		}

	}

	public static class NullOutput implements Step<Request, Request> {

		public Request execute(StepContext context, Request input) {
			Map<String, String> config = context.configuration();
			return null;
		}

	}

	public static class Slow implements Step<Request, Request> {

		public Request execute(StepContext context, Request input) {
			Map<String, String> config = context.configuration();
			try {
				Thread.sleep(2200);
				return input;
			}
			catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("slow step interrupted", failure);
			}
		}

	}

	public static class Throws implements Step<Request, Request> {

		public Request execute(StepContext context, Request input) {
			Map<String, String> config = context.configuration();
			throw new IllegalStateException("business failure");
		}

	}

	public static class NestedEcho implements Step<Nested<Request>, Nested<Request>> {

		public Nested<Request> execute(StepContext context, Nested<Request> input) {
			Map<String, String> config = context.configuration();
			return input;
		}

	}

	public static class Blocking implements Step<Request, Request> {

		public Request execute(StepContext context, Request input) {
			Map<String, String> config = context.configuration();
			OperationEvidence.count(config, "blocking", context, input.text());
			Path dir = Path.of(config.get("evidence"));
			try {
				Files.writeString(dir.resolve("entered-" + context.attemptNumber()),
						Long.toString(ProcessHandle.current().pid()));
				while (!Files.exists(dir.resolve("release-" + context.attemptNumber())))
					Thread.sleep(5);
			}
			catch (IOException failure) {
				throw new UncheckedIOException(failure);
			}
			catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("blocking step interrupted", failure);
			}
			return new Request("generation:" + context.attemptNumber());
		}

	}

	static TestApplication deployment(Map<String, String> config) {
		return deployment("fixture-v1", config);
	}

	static TestApplication deployment(String build, Map<String, String> config) {
		return new TestApplication("kernel-fixtures", build, config,
				steps(new FirstOperation(), new SecondOperation(), new ThirdOperation(), new FourthOperation(),
						new FifthOperation(), new ChangedThird(), new ChangedFourth(), new ChangedFifth(), new Echo(),
						new NullOutput(), new Slow(), new Throws(), new NestedEcho(), new Blocking()));
	}

	static Map<String, Step<?, ?>> steps(Step<?, ?>... steps) {
		Map<String, Step<?, ?>> registered = new LinkedHashMap<>();
		for (Step<?, ?> step : steps)
			registered.put(step.getClass().getName(), step);
		return registered;
	}

	static ValidatedWorkflow echo(TestApplication deployment, Class<? extends Step<?, ?>> handler,
			Duration duration) {
		return single(deployment, handler, Request.class, Request.class, duration, Terminal.SUCCEEDED);
	}

	static ValidatedWorkflow single(TestApplication deployment, Class<? extends Step<?, ?>> handler,
			java.lang.reflect.Type in, java.lang.reflect.Type out, Duration duration, Terminal terminal) {
		Definition<?, ?> definition = new Definition<>("simple", in, out,
				List.of(new Call("work", Op.declared("work", in, out)), new End(terminal, "authored reason")),
				duration);
		Placement placement = new Placement(
				List.of(new Segment("workflow", "simple", 0), new Segment("call", "work", 0)));
		// Derive the public compiler's stable placement; no second binding resolver.
		var compilation = StructuredWorkflowCompiler.compile(new Definition<>(definition.name(), in, out,
				definition.nodes(), duration == null ? Duration.ofHours(1) : duration));
		placement = compilation.bindings().getFirst().placement();
		return ValidatedWorkflow.compile(definition,
				Map.of(placement, deployment.step(handler.getName())));
	}

	static ValidatedWorkflow o01(TestApplication deployment) {
		return o01(deployment, false);
	}

	static ValidatedWorkflow o01(TestApplication deployment, boolean changedComponents) {
		List<Call> calls = List.of(new Call("first", Op.named("first", Request.class, First.class)),
				new Call("second", Op.named("second", SecondInput.class, Second.class)),
				new Call("third", Op.declared("third", Second.class, changedComponents ? Request.class : Third.class)),
				new Call("fourth",
						Op.declared("fourth", changedComponents ? Request.class : Third.class,
								changedComponents ? First.class : Fourth.class)),
				new Call("fifth", Op.declared("fifth", changedComponents ? ChangedFifthInput.class : FifthInput.class,
						Reply.class)));
		List<Node> nodes = new ArrayList<>(calls);
		nodes.add(new End(Terminal.SUCCEEDED, ""));
		Definition<?, ?> definition = new Definition<>("earlier-input", Request.class, Reply.class, nodes, null);
		var construction = StructuredWorkflowCompiler.compile(new Definition<>(definition.name(), definition.input(),
				definition.output(), nodes, Duration.ofHours(1)));
		List<Class<? extends Step<?, ?>>> handlers = List.of(FirstOperation.class, SecondOperation.class,
				changedComponents ? ChangedThird.class : ThirdOperation.class,
				changedComponents ? ChangedFourth.class : FourthOperation.class,
				changedComponents ? ChangedFifth.class : FifthOperation.class);
		Map<Placement, Step<?, ?>> selected = new LinkedHashMap<>();
		for (int i = 0; i < calls.size(); i++)
			selected.put(construction.bindings().get(i).placement(), deployment.step(handlers.get(i).getName()));
		return ValidatedWorkflow.compile(definition, selected);
	}

}
