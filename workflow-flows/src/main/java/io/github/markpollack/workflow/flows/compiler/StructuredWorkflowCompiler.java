package io.github.markpollack.workflow.flows.compiler;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/** Compiles structured Java definitions without executing application code. */
public final class StructuredWorkflowCompiler {
    private StructuredWorkflowCompiler() {}

    /** Acquires one immutable definition, analyzes it, and checks its graph lowering.
     * A compilation is construction evidence; runtime eligibility is a separate boundary.
     */
    public static Compilation<?,?> compile(Definition<?,?> definition) {
        return compileOwned(DefinitionOwnership.acquire(definition));
    }

    static Compilation<?,?> compileOwned(Definition<?,?> owned) {
        return GraphLowering.lower(RegionAnalyzer.analyze(owned));
    }
}
