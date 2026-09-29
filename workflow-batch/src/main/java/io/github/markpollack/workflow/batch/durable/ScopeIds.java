package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.compiler.WorkflowModel.ValueId;

/**
 * Framed dynamic identities; attempts never participate in scope or invocation identity.
 */
final class ScopeIds {

	private ScopeIds() {
	}

	static String root(String run) {
		return Digests.fields("scope-v1", run, "root");
	}

	static String child(RunState.Scope parent, String node) {
		return Digests.fields("scope-v1", parent.id, parent.definition, node);
	}

	static String invocation(String run, String scope, String node) {
		return Digests.fields("invocation-v2", run, scope, node);
	}

	static String localValue(ValueId value) {
		return Digests.fields(value.placement().graphName(), value.role(), value.phase());
	}

	static String value(String run, RunState.Scope scope, ValueId value) {
		return value(run, scope.id, scope.definition, localValue(value));
	}

	static String value(String run, String scope, String definition, String local) {
		return Digests.fields("value-v2", run, scope, definition, local);
	}

	static String outcome(String scope) {
		return Digests.fields("local-outcome-v1", scope);
	}

	static String receipt(String invocation) {
		return Digests.fields("return-v1", invocation);
	}

}
