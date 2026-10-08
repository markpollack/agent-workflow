package io.github.markpollack.workflow.flows.compiler;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Common structure and typed-binding analysis followed by checked graph construction.
 * RegionAnalyzer walks the owned ordered definition, deriving bindings and continuation
 * summaries. GraphLowering builds the graph from those summaries and GraphVerification
 * checks its topology and type/provenance agreement. There is no Step invocation or
 * persistence here. Public compile returns construction evidence; ValidatedWorkflow adds
 * supported-capability and executable-selection checks before runtime admission.
 */
public final class StructuredWorkflowCompiler {

	private StructuredWorkflowCompiler() {
	}

	/**
	 * Acquires one immutable definition, analyzes it, and checks its graph lowering. A
	 * compilation is construction evidence; runtime eligibility is a separate boundary.
	 */
	public static Compilation<?, ?> compile(Definition<?, ?> definition) {
		return compileOwned(DefinitionOwnership.acquire(definition));
	}

	static Compilation<?, ?> compileInferredOwned(Definition<?, ?> owned) {
		return GraphLowering.lower(RegionAnalyzer.analyzeInferred(owned));
	}

	static Compilation<?, ?> compileOwned(Definition<?, ?> owned) {
		return GraphLowering.lower(RegionAnalyzer.analyze(owned));
	}

}
