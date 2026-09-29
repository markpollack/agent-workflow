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

}
