package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.workflow.WorkflowNode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * One local database owner for validated workflows with nested same-run composites.
 * Application-supplied steps execute synchronously on the caller, outside transactions.
 * No executor, lease or scheduler. Process death releases ownership; compatible
 * applications can recover unfinished progress.
 * <p>
 * StepRegistry retains supplied objects; ExecutionCompatibility records declared facts.
 * ValidatedWorkflow owns the checked definition and binding recipes. This front door
 * coordinates their use with RuntimeLifecycle (whole-call drainage and same-run
 * exclusion), StoreOwnership (exclusive process-held database lock) and JdbcRunStore
 * (atomic saved facts).
 * <p>
 * start() admits without execution; advance() performs at most one boundary; resume()
 * repeats advancement on its caller. A call first resolves contracts, commits exact input
 * and attempt charge in a transaction, then rechecks eligibility in a separate short
 * transaction. Both transactions commit and release the store lock before Step.execute.
 * Preparation can decode values and construct input records inside its transaction.
 * Result encoding occurs outside transactions; a final transaction accepts output and
 * advances the graph atomically, including terminal success when reached.
 * <p>
 * A slow application call holds no workflow-store database lock. Distinct callers may
 * advance different runs concurrently; same-run duplicate execution refuses. The shared
 * store lock row serializes transitions, not Step calls. Transactions opened by
 * application code itself are outside this guarantee. Inspection returns immutable
 * snapshots but may persist deadline expiry. Known application failures are terminal;
 * unfinished crash recovery is not automatic retry or operator restart of a terminal run.
 */
public final class DurableWorkflows implements AutoCloseable {

	private final JdbcRunStore store;

	private final StoreOwnership ownership;

	private final RuntimeLifecycle lifecycle = new RuntimeLifecycle();

	private final StepRegistry registry;

	private final ExecutionCompatibility deployment;

	private final ExecutionPolicy policy;

	private final BoundaryHooks hooks;

	/**
	 * Open exclusive management access without loading executable application objects.
	 */
	public static DurableWorkflows open(Path database) {
		return open(database, ExecutionPolicy.DEFAULT);
	}

	public static DurableWorkflows open(Path database, ExecutionPolicy policy) {
		return new DurableWorkflows(database, null, null, policy, BoundaryHooks.NONE);
	}

	/**
	 * Acquire exclusive ownership and open/check the local store using supplied objects.
	 * Does not execute steps or resume runs automatically. The application owns the
	 * objects and must supply the compatible deployment again after process death.
	 * @param database H2 base file path without its .mv.db suffix
	 * @param registry fixed named supplied objects
	 * @param deployment declared compatibility facts
	 * @return runtime handle whose close/shutdown drains entered calls before releasing
	 * ownership
	 * @throws WorkflowRefusal for an active owner, incompatible store or startup failure
	 */
	public static DurableWorkflows open(Path database, StepRegistry registry, ExecutionCompatibility deployment) {
		return open(database, registry, deployment, ExecutionPolicy.DEFAULT);
	}

	public static DurableWorkflows open(Path database, StepRegistry registry, ExecutionCompatibility deployment,
			ExecutionPolicy policy) {
		return new DurableWorkflows(database, Objects.requireNonNull(registry), Objects.requireNonNull(deployment),
				policy, BoundaryHooks.NONE);
	}

	DurableWorkflows(Path database, StepRegistry registry, ExecutionCompatibility deployment, ExecutionPolicy policy,
			BoundaryHooks hooks) {
		this.registry = registry;
		this.deployment = deployment;
		this.policy = Objects.requireNonNull(policy);
		this.hooks = Objects.requireNonNull(hooks);
		if (Thread.currentThread().isInterrupted())
			throw new WorkflowRefusal("INTERRUPTED", "caller is already interrupted");
		ownership = StoreOwnership.acquire(database);
		try {
			store = new JdbcRunStore(ownership.database());
		}
		catch (RuntimeException | Error ex) {
			try {
				ownership.close();
			}
			catch (RuntimeException close) {
				ex.addSuppressed(close);
			}
			throw ex;
		}
	}

	/**
	 * Admit the validated workflow with its exact root input, compatibility and absolute
	 * deadline in one transaction. No Step executes. Reusing the key with identical
	 * admitted facts returns the existing run, including a terminal run.
	 * @param workflow immutable checked definition containing Steps, composites and
	 * terminals
	 * @param idempotencyKey application submission key within this database
	 * @param input value satisfying the definition's root input and strict codec contract
	 * @return admitted or identically reused durable snapshot
	 * @throws WorkflowRefusal for incompatible deployment/types, conflicting key reuse,
	 * unsupported execution constructs or store failure
	 */
	public RunSnapshot start(ValidatedWorkflow workflow, String idempotencyKey, Object input) {
		return start(workflow, idempotencyKey, input, workflow.definition().name());
	}

	/**
	 * Display text is an admitted presentation fact and does not change authored behavior
	 * or idempotency.
	 */
	public RunSnapshot start(ValidatedWorkflow workflow, String idempotencyKey, Object input, String displayName) {
		try (var activity = lifecycle.enter(null)) {
			return admit(workflow, idempotencyKey, input, displayName);
		}
	}

	private RunSnapshot admit(ValidatedWorkflow workflow, String idempotencyKey, Object input, String displayName) {
		requireSupported(workflow);
		requireText(idempotencyKey, "idempotency key");
		requireText(displayName, "display name");
		WorkflowExecutionBindings resolved = new WorkflowExecutionBindings(registry, requireDeployment(), workflow,
				policy);
		byte[] root = resolved.encode(input, workflow.definition().input());
		resolved.decode(root, workflow.definition().input());
		String compatibility = compatibility(resolved, requireDeployment().manifest(), policy);
		String admission = Digests.fields(compatibility, Digests.of(root));
		RunSnapshot result = store.transaction(tx -> {
			RunState existing = tx.byKey("root:" + idempotencyKey);
			if (existing != null) {
				compatible(existing, workflow, resolved);
				if (!existing.admission.equals(admission))
					throw new WorkflowRefusal("IDEMPOTENCY_CONFLICT", "idempotency key has different admitted facts");
				tx.observe(existing);
				return existing.snapshot();
			}
			RunState run = new RunState();
			run.id = UUID.randomUUID().toString();
			run.key = "root:" + idempotencyKey;
			run.admission = admission;
			run.compatibility = compatibility;
			run.display = displayName;
			run.authored = workflow.authoredIdentity();
			run.deployment = requireDeployment().manifest();
			run.admitted = tx.now;
			run.deadline = Math.addExact(tx.now, workflow.definition().deadline().toMillis());
			run.deadlineOrigin = workflow.deadlineOrigin();
			run.maximumAttempts = policy.maximumAttempts();
			run.maximumDepth = policy.maximumDepth();
			run.maximumInvocations = policy.maximumInvocations();
			run.rootDefinition = resolved.root();
			run.definitions = resolved.descriptors();
			run.bounds = resolved.bounds();
			RunState.Scope scope = new RunState.Scope();
			scope.id = ScopeIds.root(run.id);
			scope.definition = resolved.root();
			scope.opened = tx.now;
			scope.deadline = run.deadline;
			scope.deadlineOrigin = run.deadlineOrigin;
			scope.ready(workflow.graph().startNode());
			run.rootScope = scope.id;
			run.scopes.put(scope.id, scope);
			var rootRecipe = ScopedValues.root(workflow);
			scope.input = ScopeIds.value(run.id, scope, rootRecipe.identity());
			ScopedValues.put(run, scope, rootRecipe, root, "", "");
			run.event("ADMITTED", tx.now, run.admission);
			tx.insert(run);
			hooks.at("BEFORE_ADMISSION_COMMIT", run.id);
			return run.snapshot();
		});
		hooks.at("AFTER_ADMISSION_COMMIT", result.runId());
		return result;
	}

	/**
	 * Read an immutable saved snapshot. Observation can atomically expire an active run,
	 * but invokes no application Step and does not require an executable deployment.
	 * @param runId existing durable run identity
	 * @return snapshot after deadline observation, never a writable store object
	 * @throws WorkflowRefusal if the run is missing, runtime is closing or the store
	 * refuses
	 */
	public RunSnapshot inspect(String runId) {
		try (var activity = lifecycle.enter(null)) {
			return inspectSaved(runId);
		}
	}

	private RunSnapshot inspectSaved(String runId) {
		return store.transaction(tx -> {
			RunState run = tx.get(runId);
			tx.observe(run);
			return run.snapshot();
		});
	}

	/**
	 * Find unfinished runs after observing their deadlines. Observation persists terminal
	 * failure for expired active runs and excludes them from the returned list. Like
	 * {@link #inspect(String)}, this is not a read-only query and invokes no Step.
	 * @return immutable snapshots of runs still active after deadline observation
	 * @throws WorkflowRefusal if the runtime is closing or the store refuses
	 */
	public List<RunSnapshot> discover() {
		try (var activity = lifecycle.enter(null)) {
			return store.transaction(tx -> {
				List<RunSnapshot> result = new ArrayList<>();
				for (RunState run : tx.all()) {
					tx.observe(run);
					if (run.active())
						result.add(run.snapshot());
				}
				return List.copyOf(result);
			});
		}
	}

	/** Revoke durable continuation; does not interrupt running application code. */
	public RunSnapshot cancel(String runId, String actor, String reason) {
		try (var activity = lifecycle.enter(null)) {
			requireText(actor, "cancellation actor");
			requireText(reason, "cancellation reason");
			return store.transaction(tx -> {
				RunState run = tx.get(runId);
				tx.observe(run);
				if (run.active())
					tx.terminal(run, "CANCELLED", "CANCELLED", reason, actor);
				return run.snapshot();
			});
		}
	}

	/**
	 * Continue synchronously to terminal using saved progress. A concurrent same-run call
	 * refuses with RUN_BUSY before charging an attempt; different runs may execute
	 * concurrently. An application interrupt returns saved progress after the current
	 * outcome, restoring the flag. A compatible terminal run is returned unchanged. This
	 * does not restart terminal failures. A snapshot may still be ACTIVE if a normally
	 * returning step interrupted its caller. Cancellation/expiry can win while
	 * application code runs; they fence acceptance, not the Java call itself. Each
	 * application result is provisional until its commit.
	 * @param runId admitted run identity
	 * @param workflow compatible validated definition used for its exact recipes
	 * @return final or interrupted progress snapshot
	 * @throws WorkflowRefusal for duplicate entry, incompatible/corrupt saved facts,
	 * lifecycle refusal or infrastructure failure; these are not Step failures
	 */
	public RunSnapshot resume(String runId, ValidatedWorkflow workflow) {
		requireText(runId, "run ID");
		try (var activity = lifecycle.enter(runId)) {
			WorkflowExecutionBindings resolved = resolve(runId, workflow);
			RunSnapshot result;
			do {
				result = advanceOwned(runId, workflow, resolved, activity);
			}
			while (!result.terminal() && !activity.interrupted());
			return result;
		}
	}

	/**
	 * Advance one eligible Step, composite entry or return boundary synchronously,
	 * returning the saved snapshot. Holds the same-run guard through preparation,
	 * application entry and commit. It does not create a worker thread or continue
	 * automatically after returning ACTIVE.
	 * @param runId admitted run identity
	 * @param workflow compatible checked definition
	 * @return progress after at most one step, or an already terminal snapshot
	 * @throws WorkflowRefusal for the same lifecycle/compatibility/store reasons as
	 * resume
	 */
	public RunSnapshot advance(String runId, ValidatedWorkflow workflow) {
		requireText(runId, "run ID");
		try (var activity = lifecycle.enter(runId)) {
			return advanceOwned(runId, workflow, resolve(runId, workflow), activity);
		}
	}

	/**
	 * Execute with an already-held lifecycle activity. The preparation transaction
	 * returns only saved progress or a charged Dispatch. A Dispatch is permission to
	 * recheck entry, not permission to ignore a cancellation or expiry that wins after
	 * preparation.
	 */
	private RunSnapshot advanceOwned(String runId, ValidatedWorkflow workflow, WorkflowExecutionBindings resolved,
			RuntimeLifecycle.Activity activity) {
		// Preparation commits exact input and a charged attempt; it never calls
		// Step.execute.
		Advancement prepared = store.transaction(tx -> prepareAdvance(tx, runId, workflow, resolved));
		if (prepared instanceof Progress progress) {
			hooks.at("AFTER_PROGRESS_COMMIT", runId);
			return progress.snapshot;
		}
		return executeAttempt(runId, workflow, resolved, (Dispatch) prepared, activity);
	}

	/**
	 * Inside the caller's store transaction, observe expiry, recover/validate the logical
	 * invocation or materialize its exact effective input, then charge one bounded
	 * attempt. A terminal/ineligible run returns Progress. An unresolved invocation
	 * reuses saved input; missing or changed provenance refuses instead of reconstructing
	 * yesterday's values.
	 */
	private Advancement prepareAdvance(JdbcRunStore.Tx tx, String runId, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved) throws Exception {
		RunState run = tx.get(runId);
		compatible(run, workflow, resolved);
		tx.observe(run);
		if (!run.active())
			return new Progress(run.snapshot());
		var scope = WorkflowProgress.eligible(run);
		if (scope.lifecycle.equals("LOCAL_TERMINAL"))
			return returnComposite(tx, run, scope, resolved);
		var progress = WorkflowProgress.frontier(scope);
		var local = resolved.definition(scope.definition);
		var node = local.graph().nodeByName(progress.node);
		if (node instanceof WorkflowNode.CompositeNode)
			return enterComposite(tx, run, scope, progress, local, resolved);
		if (!(node instanceof WorkflowNode.StepNode))
			throw new WorkflowRefusal("PROGRESS_INVALID", "eligible node is not a supported call");
		var recipe = local.graph().binding(node.name());
		var invocation = WorkflowProgress.find(run, scope.id, node.name());
		if (invocation == null) {
			try {
				invocation = newInvocation(run, scope, recipe, "LEAF", local, resolved);
			}
			catch (WorkflowRefusal ex) {
				if (!ex.code().equals("INPUT_FAILED") && !ex.code().equals("ENCODE_FAILED"))
					throw ex;
				run.complete(scope, "FAILED", "INPUT_ENCODING_FAILED", ex.getMessage(), "runtime", "runtime", "",
						tx.now);
				tx.save(run);
				return new Progress(run.snapshot());
			}
		}
		return chargeAttempt(tx, run, scope, invocation, recipe, local, resolved);
	}

	private RunState.Invocation newInvocation(RunState run, RunState.Scope scope, Binding recipe, String kind,
			ValidatedWorkflow local, WorkflowExecutionBindings resolved) {
		if (run.logicalCount >= run.maximumInvocations || run.logicalCount >= run.bounds.logical())
			throw new WorkflowRefusal("COMPOSITION_LIMIT", "logical invocation budget exhausted");
		var invocation = new RunState.Invocation();
		invocation.id = ScopeIds.invocation(run.id, scope.id, recipe.placement().graphName());
		invocation.scope = scope.id;
		invocation.placement = recipe.placement().graphName();
		invocation.kind = kind;
		invocation.input = ScopeIds.value(run.id, scope, recipe.input().identity());
		invocation.output = ScopeIds.value(run.id, scope, recipe.output().identity());
		ScopedValues.materialize(run, scope, recipe.input().identity(), local, resolved, invocation.id);
		run.invocations.add(invocation);
		run.logicalCount++;
		scope.nodes.get(invocation.placement).invocation = invocation.id;
		return invocation;
	}

	/**
	 * Entry commits an exact boundary input and a single private scope, with no Step
	 * attempt.
	 */
	private Advancement enterComposite(JdbcRunStore.Tx tx, RunState run, RunState.Scope parent, RunState.Node progress,
			ValidatedWorkflow workflow, WorkflowExecutionBindings resolved) throws Exception {
		if (!progress.phase.equals("READY"))
			throw new WorkflowRefusal("PROGRESS_INVALID", "composite entry is not ready");
		var binding = workflow.graph().binding(progress.node);
		RunState.Invocation call;
		try {
			call = newInvocation(run, parent, binding, "COMPOSITE", workflow, resolved);
		}
		catch (WorkflowRefusal ex) {
			if (!ex.code().equals("INPUT_FAILED") && !ex.code().equals("ENCODE_FAILED"))
				throw ex;
			run.complete(parent, "FAILED", "INPUT_ENCODING_FAILED", ex.getMessage(), "runtime", "runtime", "", tx.now);
			tx.save(run);
			return new Progress(run.snapshot());
		}
		var child = new RunState.Scope();
		child.id = ScopeIds.child(parent, progress.node);
		child.definition = resolved.callee(parent.definition, progress.node);
		var callee = resolved.definition(child.definition);
		child.parent = parent.id;
		child.opening = call.id;
		child.returnNode = workflow.graph().unconditionalSuccessor(progress.node);
		child.depth = parent.depth + 1;
		child.opened = tx.now;
		child.deadline = Math.min(parent.deadline, Math.addExact(tx.now, callee.definition().deadline().toMillis()));
		child.deadlineOrigin = "INHERITED:" + callee.deadlineOrigin();
		child.ready(callee.graph().startNode());
		var rootRecipe = ScopedValues.root(callee);
		child.input = ScopeIds.value(run.id, child, rootRecipe.identity());
		var input = ScopedValues.verify(run, parent, binding.input().identity(), workflow);
		ScopedValues.put(run, child, rootRecipe, input.payload, call.id, input.id);
		if (run.scopes.putIfAbsent(child.id, child) != null)
			throw new WorkflowRefusal("PROGRESS_INVALID", "duplicate scope");
		call.child = child.id;
		progress.phase = "WAITING_CHILD";
		run.event("COMPOSITE_ENTERED", tx.now, call.id + ":" + child.id);
		tx.save(run);
		hooks.at("BEFORE_COMPOSITE_ENTRY_COMMIT", run.id);
		return new Progress(run.snapshot());
	}

	/**
	 * Propagate exactly one accepted local outcome. Any enclosing completion is folded,
	 * but not unwound.
	 */
	private Advancement returnComposite(JdbcRunStore.Tx tx, RunState run, RunState.Scope child,
			WorkflowExecutionBindings resolved) throws Exception {
		var parent = run.scopes.get(child.parent);
		if (parent == null || !parent.open())
			throw new WorkflowRefusal("PROGRESS_INVALID", "return lacks live parent");
		var call = WorkflowProgress.invocation(run, child.opening);
		var progress = parent.nodes.get(call.placement);
		var workflow = resolved.definition(parent.definition);
		var binding = workflow.graph().binding(call.placement);
		var outcome = child.localOutcome;
		String output = "";
		if (outcome.status().equals("SUCCEEDED")) {
			var callee = resolved.definition(child.definition);
			var value = ScopedValues.verify(run, child, callee.terminal().successValue(), callee);
			ScopedValues.put(run, parent, workflow.values().get(binding.output().identity()), value.payload, call.id,
					value.id);
			output = call.output;
		}
		var receipt = new RunState.Return(ScopeIds.receipt(call.id), call.id, child.id, outcome.id(), output, tx.now);
		if (run.returns.putIfAbsent(call.id, receipt) != null)
			throw new WorkflowRefusal("PROGRESS_INVALID", "duplicate return");
		call.status = "SETTLED";
		call.outcome = outcome.id();
		child.lifecycle = "RETURNED";
		progress.phase = "SETTLED";
		progress.settlement = receipt.id();
		run.event("COMPOSITE_RETURNED", tx.now, receipt.id());
		if (outcome.status().equals("SUCCEEDED"))
			continueAfter(tx, run, parent, workflow, call.placement);
		else
			run.complete(parent, "FAILED", outcome.code(), outcome.message(), "composite", outcome.id(), "", tx.now);
		tx.save(run);
		hooks.at("BEFORE_COMPOSITE_RETURN_COMMIT", run.id);
		return new Progress(run.snapshot());
	}

	private Advancement chargeAttempt(JdbcRunStore.Tx tx, RunState run, RunState.Scope scope,
			RunState.Invocation invocation, Binding recipe, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved) throws Exception {
		if (invocation.attempts.size() >= run.maximumAttempts) {
			run.complete(scope, "FAILED", "ATTEMPTS_EXHAUSTED", "finite execution attempt allowance exhausted",
					"runtime", invocation.id, "", tx.now);
			tx.save(run);
			return new Progress(run.snapshot());
		}
		for (var previous : invocation.attempts)
			if (previous.disposition.equals("UNRESOLVED"))
				previous.disposition = "REATTEMPTED";
		var attempt = new RunState.Attempt();
		attempt.id = UUID.randomUUID().toString();
		attempt.number = invocation.attempts.size() + 1;
		attempt.charged = tx.now;
		Object input = ScopedValues.decode(run, scope, recipe.input().identity(), workflow, resolved);
		invocation.attempts.add(attempt);
		scope.nodes.get(invocation.placement).phase = "ENTERED";
		run.event("DISPATCHED", tx.now, invocation.id + ":" + attempt.id);
		tx.save(run);
		hooks.at("BEFORE_DISPATCH_COMMIT", run.id);
		StepContext context = new StepContext(run.id, invocation.id, attempt.id, attempt.number,
				Instant.ofEpochMilli(scope.deadline));
		return new Dispatch(scope.id, scope.definition, recipe, input, context);
	}

	/**
	 * Recheck a committed Dispatch in its own transaction before invoking application
	 * code. Invocation and output encoding have separate failure classifications. Both
	 * complete outside store transactions, and interruption is captured before any
	 * outcome persistence. Preparation, pre-entry and result-store failures escape as
	 * infrastructure/refusal errors; they are never caught as application Step failures.
	 * Non-Linkage JVM Errors propagate.
	 */
	private RunSnapshot executeAttempt(String runId, ValidatedWorkflow workflow, WorkflowExecutionBindings resolved,
			Dispatch dispatch, RuntimeLifecycle.Activity activity) {
		hooks.at("AFTER_DISPATCH_COMMIT", runId);
		boolean mayEnter = store.transaction(tx -> {
			RunState run = tx.get(runId);
			compatible(run, workflow, resolved);
			tx.observe(run);
			if (!run.active() || !run.scopes.get(dispatch.scope).open())
				return false;
			current(run, dispatch);
			return true;
		});
		if (!mayEnter)
			return inspectSaved(runId);
		byte[] output;
		try {
			Object value;
			try {
				value = resolved.execute(dispatch.definition, dispatch.recipe.placement().graphName(), dispatch.input,
						dispatch.context);
			}
			catch (Exception | LinkageError failure) {
				return failAttempt(runId, workflow, resolved, dispatch, activity, "STEP_FAILED", failure);
			}
			try {
				output = resolved.encode(value,
						resolved.definition(dispatch.definition)
							.values()
							.get(dispatch.recipe.output().identity())
							.declaration());
			}
			catch (Exception | LinkageError failure) {
				return failAttempt(runId, workflow, resolved, dispatch, activity, "OUTPUT_ENCODING_FAILED", failure);
			}
		}
		finally {
			// A normally returned value or its codec can also leave an interrupt set.
			activity.captureInterrupt(null);
		}
		hooks.at("AFTER_HANDLER_RETURN", runId);
		byte[] committedOutput = output;
		RunSnapshot result = store
			.transaction(tx -> commitResult(tx, runId, workflow, resolved, dispatch, committedOutput));
		hooks.at("AFTER_RESULT_COMMIT", runId);
		return result;
	}

	/**
	 * Within one transaction, accept only the still-current attempt of an active
	 * compatible run. Cancellation/expiry wins without accepting a late output. Otherwise
	 * save exact output, invocation/attempt disposition, continuation and events
	 * together. A graph terminal successor supplies its explicit completion outcome.
	 */
	private RunSnapshot commitResult(JdbcRunStore.Tx tx, String runId, ValidatedWorkflow workflow,
			WorkflowExecutionBindings resolved, Dispatch dispatch, byte[] output) throws Exception {
		RunState run = tx.get(runId);
		compatible(run, workflow, resolved);
		tx.observe(run);
		var scope = run.scopes.get(dispatch.scope);
		if (!run.active() || !scope.open())
			return run.snapshot();
		var invocation = current(run, dispatch);
		var local = resolved.definition(scope.definition);
		ScopedValues.put(run, scope, local.values().get(dispatch.recipe.output().identity()), output, invocation.id,
				"");
		invocation.status = "SETTLED";
		invocation.outcome = "COMMITTED";
		invocation.attempts.getLast().disposition = "COMMITTED";
		var node = scope.nodes.get(invocation.placement);
		node.phase = "SETTLED";
		node.settlement = invocation.id;
		run.event("RESULT_COMMITTED", tx.now, invocation.id + ":" + invocation.output);
		continueAfter(tx, run, scope, local, invocation.placement);
		tx.save(run);
		hooks.at("BEFORE_RESULT_COMMIT", run.id);
		return run.snapshot();
	}

	/** Graph successor and directly reached terminal commit with the result/return. */
	private void continueAfter(JdbcRunStore.Tx tx, RunState run, RunState.Scope scope, ValidatedWorkflow workflow,
			String predecessor) {
		String next = workflow.graph().unconditionalSuccessor(predecessor);
		var progress = scope.ready(next);
		if (workflow.graph().nodeByName(next) instanceof WorkflowNode.TerminalNode terminal) {
			String output = terminal.successValue() == null ? ""
					: ScopedValues.verify(run, scope, terminal.successValue(), workflow).id;
			progress.phase = "SETTLED";
			progress.settlement = ScopeIds.outcome(scope.id);
			run.complete(scope, terminal.intent().name(), "AUTHORED_" + terminal.intent().name(), terminal.reason(),
					"workflow", next, output, tx.now);
			hooks.at("BEFORE_TERMINAL_COMMIT", run.id);
		}
	}

	/**
	 * Retrieve a successful run's exact result, checking compatible supplied contracts
	 * and saved output provenance. Copies bytes inside a transaction and decodes after
	 * it. Does not execute a Step or reopen a terminal run.
	 * @return decoded result matching the workflow's output Type
	 * @throws WorkflowRefusal if no success result exists or compatibility/integrity
	 * fails
	 */
	public Object result(String runId, ValidatedWorkflow workflow) {
		try (var activity = lifecycle.enter(null)) {
			WorkflowExecutionBindings resolved = resolve(runId, workflow);
			byte[] bytes = store.transaction(tx -> {
				RunState run = tx.get(runId);
				compatible(run, workflow, resolved);
				if (!run.status.equals("SUCCEEDED"))
					throw new WorkflowRefusal("NO_SUCCESS_RESULT", "run has no successful terminal result");
				RunState.Value value = ScopedValues.verify(run, run.scopes.get(run.rootScope),
						workflow.terminal().successValue(), workflow);
				if (!run.output.equals(value.id))
					throw new WorkflowRefusal("VALUE_CHANGED", "terminal output identity differs");
				return value.payload.clone();
			});
			return resolved.decode(bytes, workflow.definition().output());
		}
	}

	/**
	 * Persist a known application or output-codec failure, after clearing/capturing any
	 * application interruption. A concurrent terminal winner is preserved. Failure of
	 * this store transition propagates; it must not be converted into another application
	 * failure.
	 */
	private RunSnapshot failAttempt(String runId, ValidatedWorkflow workflow, WorkflowExecutionBindings resolved,
			Dispatch dispatch, RuntimeLifecycle.Activity activity, String code, Throwable failure) {
		activity.captureInterrupt(failure);
		String message = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
		return store.transaction(tx -> {
			RunState run = tx.get(runId);
			compatible(run, workflow, resolved);
			tx.observe(run);
			var scope = run.scopes.get(dispatch.scope);
			if (!run.active() || !scope.open())
				return run.snapshot();
			var call = current(run, dispatch);
			call.status = "SETTLED";
			call.outcome = code;
			call.attempts.getLast().disposition = code;
			var node = scope.nodes.get(call.placement);
			node.phase = "SETTLED";
			node.settlement = call.id;
			run.complete(scope, "FAILED", code, message, "runtime", call.id, "", tx.now);
			tx.save(run);
			return run.snapshot();
		});
	}

	private ExecutionCompatibility requireDeployment() {
		if (deployment == null)
			throw new WorkflowRefusal("DEPLOYMENT_REQUIRED", "execution requires application step registration");
		return deployment;
	}

	/**
	 * Check the supplied objects, full types and codec without executing application
	 * Steps, then compare their frozen names and compatibility facts with the saved run
	 * in a transaction. Saved metadata never vouches for current objects.
	 */
	private WorkflowExecutionBindings resolve(String runId, ValidatedWorkflow workflow) {
		requireSupported(workflow);
		ExecutionCompatibility supplied = requireDeployment();
		WorkflowExecutionBindings resolved = new WorkflowExecutionBindings(registry, supplied, workflow, policy);
		store.transaction(tx -> {
			compatible(tx.get(runId), workflow, resolved);
			return null;
		});
		return resolved;
	}

	private static void requireSupported(ValidatedWorkflow workflow) {
		Objects.requireNonNull(workflow);
		if (!Set.of(Capability.OPERATION, Capability.TERMINAL, Capability.CHILD).containsAll(workflow.capabilities()))
			throw new WorkflowRefusal("UNSUPPORTED_CAPABILITY", "unsupported execution construct");
	}

	private static RunState.Invocation current(RunState run, Dispatch dispatch) {
		var scope = run.scopes.get(dispatch.scope);
		var call = WorkflowProgress.find(run, scope.id, dispatch.recipe.placement().graphName());
		if (call == null || !scope.open() || !call.status.equals("UNRESOLVED")
				|| !call.id.equals(dispatch.context.invocationId()) || call.attempts.isEmpty()
				|| !call.attempts.getLast().id.equals(dispatch.context.attemptId())
				|| !call.attempts.getLast().disposition.equals("UNRESOLVED")
				|| !scope.definition.equals(dispatch.definition))
			throw new WorkflowRefusal("ATTEMPT_CHANGED", "current execution attempt differs");
		return call;
	}

	private static String compatibility(WorkflowExecutionBindings resolved, ExecutionCompatibility.Manifest manifest,
			ExecutionPolicy policy) {
		// The exact closure is compared separately too; a digest never validates
		// duplicated fields.
		List<String> fields = new ArrayList<>();
		fields.add("durable-run-v6");
		fields.add(resolved.root());
		fields.add(Integer.toString(resolved.descriptors().size()));
		new TreeMap<>(resolved.descriptors()).forEach((key, d) -> {
			fields.add(key);
			fields.add(d.identity());
		});
		fields.add(manifest.identity());
		fields.add(policy.identity());
		return Digests.fields(fields.toArray(String[]::new));
	}

	private void compatible(RunState run, ValidatedWorkflow workflow, WorkflowExecutionBindings resolved) {
		var supplied = requireDeployment().manifest();
		if (!supplied.equals(run.deployment))
			throw new WorkflowRefusal("COMPATIBILITY",
					"supplied deployment/configuration/codec/runtime differs from admitted manifest");
		if (run.maximumAttempts != policy.maximumAttempts() || run.maximumDepth != policy.maximumDepth()
				|| run.maximumInvocations != policy.maximumInvocations() || !run.bounds.equals(resolved.bounds())
				|| !Objects.equals(run.authored, workflow.authoredIdentity())
				|| !run.rootDefinition.equals(resolved.root()) || !run.definitions.equals(resolved.descriptors())
				|| !run.compatibility.equals(compatibility(resolved, supplied, policy)))
			throw new WorkflowRefusal("COMPATIBILITY",
					"authored behavior, definition selection or execution policy differs");
		WorkflowProgress.validate(run, resolved);
	}

	private static void requireText(String text, String field) {
		if (text == null || text.isBlank())
			throw new IllegalArgumentException(field + " required");
	}

	/**
	 * Preparation result: detached charged work, or durable progress requiring no Step
	 * entry.
	 */
	private sealed interface Advancement permits Dispatch, Progress {

	}

	private record Dispatch(String scope, String definition, Binding recipe, Object input,
			StepContext context) implements Advancement {
	}

	private record Progress(RunSnapshot snapshot) implements Advancement {
	}

	/**
	 * Stop accepting calls and wait up to the supplied duration for active calls to
	 * finish. False means shutdown is incomplete: store and ownership remain held; call
	 * again after application work returns. Never interrupts a step, closes its
	 * dependencies, or cancels a run.
	 */
	public boolean shutdown(Duration timeout) {
		return lifecycle.shutdown(timeout, () -> {
			store.close();
			ownership.close();
		});
	}

	/**
	 * Drain for up to thirty seconds; incomplete drainage refuses while retaining
	 * ownership.
	 */
	@Override
	public void close() {
		if (!shutdown(Duration.ofSeconds(30)))
			throw new WorkflowRefusal("SHUTDOWN_INCOMPLETE", "application calls remain active; ownership retained");
	}

}
