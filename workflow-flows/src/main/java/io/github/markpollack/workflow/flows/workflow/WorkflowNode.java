package io.github.markpollack.workflow.flows.workflow;

import java.lang.reflect.Type;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.ValueId;

/**
 * Immutable semantic nodes. Application Step objects and predicates live outside this
 * graph.
 */
public sealed interface WorkflowNode {

	String name();

	/**
	 * One ordinary operation's full implementation requirement; bindings are keyed by
	 * this node ID.
	 */
	record StepNode(String name, Type input, Type output) implements WorkflowNode {
	}

	/** A typed local call to reusable definition data; supplied objects remain outside the graph. */
    record CompositeNode(String name, Type input, Type output, String authoredDefinition) implements WorkflowNode {
    }

    /** Explicit workflow outcome, with a selected value only for success. */
	record TerminalNode(String name, Terminal intent, String reason, ValueId successValue) implements WorkflowNode {
	}

	/** Structural analysis node, not an application operation. */
	record ControlNode(String name, String kind, Type input, Type output) implements WorkflowNode {
	}

	record DecisionNode(String name, Type input, Type output, String joinNodeName) implements WorkflowNode {
	}

	record LoopCheckNode(String name, Type input) implements WorkflowNode {
	}

	record LoopExitNode(String name) implements WorkflowNode {
	}

	record ForkNode(String name, String joinNodeName) implements WorkflowNode {
	}

}
