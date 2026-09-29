package io.github.markpollack.workflow.flows.compiler;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static org.assertj.core.api.Assertions.*;

class CompositeCompilerTest {

	public record Request(String value) {
	}

	public record PrivateValue(String value) {
	}

	public record Reply(String value) {
	}

	static final class First implements Step<Request, PrivateValue> {

		public PrivateValue execute(StepContext c, Request r) {
			throw new AssertionError("compiler invoked Step");
		}

	}

	static final class Last implements Step<PrivateValue, Reply> {

		public Reply execute(StepContext c, PrivateValue r) {
			throw new AssertionError("compiler invoked Step");
		}

	}

	static final class Leak implements Step<PrivateValue, Reply> {

		public Reply execute(StepContext c, PrivateValue r) {
			throw new AssertionError("compiler invoked Step");
		}

	}

	@Test
	void fluentAndProgrammaticReferencesShareOwnedValidationAndPrivateValueBoundary() {
		var first = new First();
		var last = new Last();
		var body = Workflows.define("poll")
			.then("fetch", first)
			.then("assess", last)
			.terminate(Terminal.SUCCEEDED)
			.build();
		var fluent = Workflows.define("caller").subWorkflow("poll", body).terminate(Terminal.SUCCEEDED).build();
		var nodes = new ArrayList<Node>(List.of(new Child("poll", body), new End(Terminal.SUCCEEDED, "")));
		var raw = new Definition<>("caller", Request.class, Reply.class, nodes, null);
		var owned = ValidatedWorkflow.compile(raw, Map.of());
		nodes.clear();
		assertThat(owned.authoredIdentity()).isEqualTo(fluent.authoredIdentity());
		assertThat(owned.children().values()).containsExactly(body);
		assertThat(owned.graph().nodeByName(owned.graph().startNode())).isInstanceOf(WorkflowNode.CompositeNode.class);
		assertThatThrownBy(() -> Workflows.define("leak")
			.subWorkflow("poll", body)
			.then("leaked", new Leak())
			.terminate(Terminal.SUCCEEDED)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unbound");
		assertThatThrownBy(() -> owned.children().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rawNestedSelectionChecksTransitiveAuthoredPolicy() {
		var first = new First();
		var last = new Last();
		var rawLeaf = new Definition<>("leaf", Request.class, Reply.class,
				List.of(new Call("first", Op.named("first", Request.class, PrivateValue.class)),
						new Call("last", Op.named("last", PrivateValue.class, Reply.class)),
						new End(Terminal.SUCCEEDED, "")),
				null);
		var leaf = ValidatedWorkflow.compile(rawLeaf, List.of(first, last), DeadlinePolicy.DEFAULT);
		var rawWrapper = new Definition<>("wrapper", Request.class, Reply.class,
				List.of(new Child("child", rawLeaf), new End(Terminal.SUCCEEDED, "")), null);
		var placement = Coordinates.node(Coordinates.root(rawWrapper), rawWrapper.nodes().getFirst(), 0);
		var wrapper = ValidatedWorkflow.compile(rawWrapper, Map.of(), Map.of(placement, leaf), DeadlinePolicy.DEFAULT);
		var rawRoot = new Definition<>("root", Request.class, Reply.class,
				List.of(new Child("wrapper", rawWrapper), new End(Terminal.SUCCEEDED, "")), null);
		var rootPosition = Coordinates.node(Coordinates.root(rawRoot), rawRoot.nodes().getFirst(), 0);
		assertThat(ValidatedWorkflow.compile(rawRoot, Map.of(), Map.of(rootPosition, wrapper), DeadlinePolicy.DEFAULT)
			.children()
			.values()).containsExactly(wrapper);
		var changedLeaf = new Definition<>(rawLeaf.name(), rawLeaf.input(), rawLeaf.output(), rawLeaf.nodes(),
				Duration.ofHours(1));
		var changed = ValidatedWorkflow.compile(changedLeaf, List.of(first, last), DeadlinePolicy.DEFAULT);
		assertThat(changed.definition().deadline()).isEqualTo(leaf.definition().deadline());
		assertThat(changed.authoredIdentity()).isNotEqualTo(leaf.authoredIdentity());
		assertThatThrownBy(() -> ValidatedWorkflow.compile(rawWrapper, Map.of(), Map.of(placement, changed),
				DeadlinePolicy.DEFAULT))
			.hasMessageContaining("child definition disagreement");
	}

	@Test
	void selfAndMutualCyclesRefuseDuringOwnershipWithoutRecursiveExpansion() {
		List<Node> aNodes = new ArrayList<>(), bNodes = new ArrayList<>();
		var a = new Definition<>("a", Request.class, Reply.class, aNodes, null);
		var b = new Definition<>("b", Request.class, Reply.class, bNodes, null);
		aNodes.add(new Child("self", a));
		assertThatThrownBy(() -> ValidatedWorkflow.compile(a, Map.of())).hasMessageContaining("cyclic definition");
		aNodes.clear();
		aNodes.add(new Child("b", b));
		bNodes.add(new Child("a", a));
		assertThatThrownBy(() -> ValidatedWorkflow.compile(a, Map.of())).hasMessageContaining("cyclic definition");
	}

	static final class Echo implements Step<Request, Request> {

		public Request execute(StepContext context, Request input) {
			throw new AssertionError("compiler invoked Step");
		}

	}

	@Test
	void rawDeclarationPreservesExplicitNestedObjectsBeyondAuthoredIdentity() {
		var fast = new Echo();
		var careful = new Echo();
		var f = Workflows.define("poll").then("step", fast).terminate(Terminal.SUCCEEDED).build();
		var c = Workflows.define("poll").then("step", careful).terminate(Terminal.SUCCEEDED).build();
		var declaration = new Definition<>("wrapper", Request.class, Request.class,
				List.of(new Child("poll", f), new End(Terminal.SUCCEEDED, "")), null);
		var wrong = Workflows.define("wrapper").subWorkflow("poll", c).terminate(Terminal.SUCCEEDED).build();
		var root = new Definition<>("root", Request.class, Request.class,
				List.of(new Child("wrapper", declaration), new End(Terminal.SUCCEEDED, "")), null);
		var at = Coordinates.node(Coordinates.root(root), root.nodes().getFirst(), 0);
		assertThat(f.authoredIdentity()).isEqualTo(c.authoredIdentity());
		assertThatThrownBy(() -> ValidatedWorkflow.compile(root, Map.of(), Map.of(at, wrong), DeadlinePolicy.DEFAULT))
			.hasMessageContaining("child reference selection disagreement");
		var equivalent = Workflows.define("wrapper")
			.subWorkflow("poll", Workflows.define("poll").then("step", fast).terminate(Terminal.SUCCEEDED).build())
			.terminate(Terminal.SUCCEEDED)
			.build();
		assertThat(ValidatedWorkflow.compile(root, Map.of(), Map.of(at, equivalent), DeadlinePolicy.DEFAULT)
			.children()
			.get(at)).isSameAs(equivalent);
	}

	private static Definition<?, ?> diamond(String name, Definition<?, ?> a, Definition<?, ?> b) {
		return new Definition<>(name, Request.class, Request.class,
				List.of(new Child("a", a), new Child("b", b), new End(Terminal.SUCCEEDED, "")), null);
	}

	private static ValidatedWorkflow diamond(String name, ValidatedWorkflow a, ValidatedWorkflow b) {
		return Workflows.define(name).subWorkflow("a", a).subWorkflow("b", b).terminate(Terminal.SUCCEEDED).build();
	}

	@Test
	void rawDiamondDagIsValidatedByUniqueDefinitionAndSelectionPairs() {
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			var step = new Echo();
			Definition<?, ?> a = new Definition<>("leaf", Request.class, Request.class, List
				.of(new Call("echo", Op.named("echo", Request.class, Request.class)), new End(Terminal.SUCCEEDED, "")),
					null);
			var b = a;
			var va = Workflows.define("leaf").then("echo", step).terminate(Terminal.SUCCEEDED).build();
			var vb = va;
			for (int i = 0; i < 24; i++) {
				var nextA = diamond("a" + i, a, b);
				var nextB = diamond("b" + i, a, b);
				var nextVa = diamond("a" + i, va, vb);
				var nextVb = diamond("b" + i, va, vb);
				a = nextA;
				b = nextB;
				va = nextVa;
				vb = nextVb;
			}
			var root = diamond("root", a, b);
			var expected = diamond("root", va, vb);
			var actual = ValidatedWorkflow.compile(root, Map.of(),
					Map.of(Coordinates.node(Coordinates.root(root), root.nodes().get(0), 0), va,
							Coordinates.node(Coordinates.root(root), root.nodes().get(1), 1), vb),
					DeadlinePolicy.DEFAULT);
			assertThat(actual.authoredIdentity()).isEqualTo(expected.authoredIdentity());
			assertThat(actual.children().values()).containsExactlyInAnyOrder(va, vb);
		});
	}

}
