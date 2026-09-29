package io.github.markpollack.workflow.flows.workflow;

/**
 * Typed condition on a {@link WorkflowEdge} — replaces the nullable
 * {@code Predicate + Function + label} design. Each variant carries only the data it
 * needs; the executor matches on variants, not on nullability.
 * <p>
 * Validated against Camunda ({@code ExclusiveGatewayActivityBehavior}) and
 * Automatiko/jBPM ({@code SplitInstance}) — conditions belong on edges, evaluated once at
 * the gateway/decision node.
 */
public sealed interface EdgeCondition permits EdgeCondition.Unconditional, EdgeCondition.BooleanGuard,
		EdgeCondition.OptionMatch, EdgeCondition.GateMatch, EdgeCondition.BranchIndex, EdgeCondition.ErrorMatch,
		EdgeCondition.LoopContinue, EdgeCondition.LoopExit {

	/** Sequential flow — always taken. */
	record Unconditional() implements EdgeCondition {
	}

	/** Branch: true or false path from a {@code GatewayNode}. */
	record BooleanGuard(boolean value) implements EdgeCondition {
	}

	/** Decision: LLM chose this option name. */
	record OptionMatch(String optionName) implements EdgeCondition {
	}

	/** Gate: matches a specific gate evaluation outcome. */
	record GateMatch(GateDecision decision) implements EdgeCondition {
	}

	/** Parallel: identifies which fork branch (0-indexed). */
	record BranchIndex(int index) implements EdgeCondition {
	}

	/** Error edge: exception type match. */
	record ErrorMatch(Class<? extends Exception> exType) implements EdgeCondition {
	}

	/** Loop back-edge: predicate says continue iterating. */
	record LoopContinue() implements EdgeCondition {
	}

	/** Loop exit-edge: predicate says done. */
	record LoopExit() implements EdgeCondition {
	}

}
