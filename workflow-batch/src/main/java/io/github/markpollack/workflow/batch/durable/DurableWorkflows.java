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
 * One local database owner for validated sequential workflows. Application-supplied steps
 * execute synchronously on the caller, outside transactions. No executor, lease or
 * scheduler. Process death releases ownership; compatible applications can recover
 * unfinished progress.
 * <p>
 * StepRegistry retains supplied objects; ExecutionCompatibility records declared facts.
 * ValidatedWorkflow owns the checked definition and binding recipes. This front door
 * coordinates their use with RuntimeLifecycle (whole-call drainage and same-run
 * exclusion), StoreOwnership (exclusive process-held database lock) and JdbcRunStore
 * (atomic saved facts).
 * <p>
 * start() admits without execution; advance() performs at most one boundary; resume()
 * repeats advancement on its caller. A call first resolves contracts, commits exact input
 * and attempt charge, rechecks eligibility, calls Step.execute outside the transaction,
 * then accepts the result in a new transaction. Inspection returns immutable snapshots.
 * Known application failures are terminal; unfinished crash recovery is not automatic
 * retry or operator restart of a terminal run.
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
	 * Admit the validated sequence with its exact root input, compatibility and absolute
	 * deadline in one transaction. No Step executes. Reusing the key with identical
	 * admitted facts returns the existing run, including a terminal run.
	 * @param workflow immutable checked definition; only the sequential capability is
	 * admitted
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
		requireSequential(workflow);
		requireText(idempotencyKey, "idempotency key");
		requireText(displayName, "display name");
		ResolvedApplication resolved = new ResolvedApplication(registry, requireDeployment(), workflow);
		byte[] root = resolved.encode(input, workflow.definition().input());
		resolved.decode(root, workflow.definition().input());
		String compatibility = compatibility(workflow, resolved.selections(), requireDeployment().manifest(), policy);
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
			run.node = workflow.graph().startNode();
			run.selections = resolved.selections();
			var rootRecipe = workflow.values()
				.values()
				.stream()
				.filter(v -> v.identity().role().equals("root"))
				.findFirst()
				.orElseThrow(() -> new WorkflowRefusal("BINDING_INVALID", "compiler root recipe missing"));
			putValue(run, rootRecipe, root, "");
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

	/** Find unfinished runs. This is observation, not a background execution facility. */
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
			ResolvedApplication resolved = resolve(runId, workflow);
			RunSnapshot result;
			do {
				result = advanceOwned(runId, workflow, resolved, activity);
			}
			while (!result.terminal() && !activity.interrupted());
			return result;
		}
	}

	/**
	 * Advance one eligible step or terminal boundary synchronously, returning the saved
	 * snapshot. Holds the same-run guard through preparation, application entry and
	 * commit. It does not create a worker thread or continue automatically after
	 * returning ACTIVE.
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
	private RunSnapshot advanceOwned(String runId, ValidatedWorkflow workflow, ResolvedApplication resolved,
			RuntimeLifecycle.Activity activity) {
		// Preparation commits exact input and a charged attempt; it never calls
		// Step.execute.
		Advancement prepared = store.transaction(tx -> prepareAdvance(tx, runId, workflow, resolved));
		if (prepared instanceof Progress progress)
			return progress.snapshot;
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
			ResolvedApplication resolved) throws Exception {
		RunState run = tx.get(runId);
		compatible(run, workflow, resolved);
		tx.observe(run);
		if (!run.active())
			return new Progress(run.snapshot());
		if (workflow.graph().nodeByName(run.node) instanceof WorkflowNode.TerminalNode) {
			finish(tx, run, workflow);
			hooks.at("BEFORE_TERMINAL_COMMIT", run.id);
			return new Progress(run.snapshot());
		}
		var recipe = workflow.graph().binding(run.node);
		RunState.Invocation invocation;
		invocation = findInvocation(run, run.node);
		if (invocation == null) {
			invocation = new RunState.Invocation();
			invocation.id = invocationId(run.id, recipe.placement());
			invocation.placement = recipe.placement().graphName();
			invocation.input = valueId(recipe.input().identity());
			invocation.output = valueId(recipe.output().identity());
			try {
				materialize(tx, run, recipe.input().identity(), workflow, resolved, invocation.id);
			}
			catch (WorkflowRefusal ex) {
				if (!ex.code().equals("INPUT_FAILED") && !ex.code().equals("ENCODE_FAILED"))
					throw ex;
				tx.terminal(run, "FAILED", "INPUT_ENCODING_FAILED", ex.getMessage(), "runtime");
				return new Progress(run.snapshot());
			}
			run.invocations.add(invocation);
		}
		validateInvocation(run, invocation, recipe);
		return chargeAttempt(tx, run, invocation, recipe, workflow, resolved);
	}

	/**
	 * Within preparation, enforce the saved finite attempt allowance, decode the exact
	 * input and atomically save the new attempt/event. Returns a detached Dispatch
	 * carrying the decoded input and attempt metadata. No application Step runs in this
	 * transaction.
	 */
	private Advancement chargeAttempt(JdbcRunStore.Tx tx, RunState run, RunState.Invocation invocation, Binding recipe,
			ValidatedWorkflow workflow, ResolvedApplication resolved) throws Exception {
		if (invocation.attempts.size() >= run.maximumAttempts) {
			tx.terminal(run, "FAILED", "ATTEMPTS_EXHAUSTED", "finite execution attempt allowance exhausted", "runtime");
			return new Progress(run.snapshot());
		}
		for (var previous : invocation.attempts)
			if (previous.disposition.equals("UNRESOLVED"))
				previous.disposition = "REATTEMPTED";
		RunState.Attempt attempt = new RunState.Attempt();
		attempt.id = UUID.randomUUID().toString();
		attempt.number = invocation.attempts.size() + 1;
		attempt.charged = tx.now;
		Object input = decode(tx, run, recipe.input().identity(), workflow, resolved);
		invocation.attempts.add(attempt);
		run.event("DISPATCHED", tx.now, invocation.id + ":" + attempt.id);
		tx.save(run);
		hooks.at("BEFORE_DISPATCH_COMMIT", run.id);
		StepContext context = new StepContext(run.id, invocation.id, attempt.id, attempt.number,
				Instant.ofEpochMilli(run.deadline), requireDeployment().configuration());
		return new Dispatch(recipe, input, context);
	}

	/**
	 * Recheck a committed Dispatch in its own transaction before invoking application
	 * code. Invocation and output encoding have separate failure classifications. Both
	 * complete outside store transactions, and interruption is captured before any
	 * outcome persistence. Preparation, pre-entry and result-store failures escape as
	 * infrastructure/refusal errors; they are never caught as application Step failures.
	 * Non-Linkage JVM Errors propagate.
	 */
	private RunSnapshot executeAttempt(String runId, ValidatedWorkflow workflow, ResolvedApplication resolved,
			Dispatch dispatch, RuntimeLifecycle.Activity activity) {
		hooks.at("AFTER_DISPATCH_COMMIT", runId);
		boolean mayEnter = store.transaction(tx -> {
			RunState run = tx.get(runId);
			compatible(run, workflow, resolved);
			tx.observe(run);
			if (!run.active())
				return false;
			current(run, dispatch.context, dispatch.recipe);
			return true;
		});
		if (!mayEnter)
			return inspectSaved(runId);
		byte[] output;
		try {
			Object value;
			try {
				value = resolved.execute(dispatch.recipe.placement().graphName(), dispatch.input, dispatch.context);
			}
			catch (Exception | LinkageError failure) {
				return failAttempt(runId, workflow, resolved, dispatch, activity, "STEP_FAILED", failure);
			}
			try {
				output = resolved.encode(value,
						workflow.values().get(dispatch.recipe.output().identity()).declaration());
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
			ResolvedApplication resolved, Dispatch dispatch, byte[] output) throws Exception {
		RunState run = tx.get(runId);
		compatible(run, workflow, resolved);
		tx.observe(run);
		if (!run.active())
			return run.snapshot();
		RunState.Invocation invocation = current(run, dispatch.context, dispatch.recipe);
		putValue(run, workflow.values().get(dispatch.recipe.output().identity()), output, invocation.id);
		invocation.status = "COMMITTED";
		invocation.attempts.getLast().disposition = "COMMITTED";
		run.node = workflow.graph().unconditionalSuccessor(dispatch.recipe.placement().graphName());
		run.event("RESULT_COMMITTED", tx.now, invocation.id + ":" + invocation.output + ":next=" + run.node);
		if (workflow.graph().nodeByName(run.node) instanceof WorkflowNode.TerminalNode) {
			finish(tx, run, workflow);
			hooks.at("BEFORE_TERMINAL_COMMIT", run.id);
		}
		else
			tx.save(run);
		hooks.at("BEFORE_RESULT_COMMIT", run.id);
		return run.snapshot();
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
			ResolvedApplication resolved = resolve(runId, workflow);
			byte[] bytes = store.transaction(tx -> {
				RunState run = tx.get(runId);
				compatible(run, workflow, resolved);
				if (!run.status.equals("SUCCEEDED"))
					throw new WorkflowRefusal("NO_SUCCESS_RESULT", "run has no successful terminal result");
				RunState.Value value = verifiedValue(tx, run, workflow.terminal().successValue(), workflow);
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
	private RunSnapshot failAttempt(String runId, ValidatedWorkflow workflow, ResolvedApplication resolved,
			Dispatch dispatch, RuntimeLifecycle.Activity activity, String code, Throwable failure) {
		activity.captureInterrupt(failure);
		String message = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
		return store.transaction(tx -> {
			RunState run = tx.get(runId);
			compatible(run, workflow, resolved);
			tx.observe(run);
			if (!run.active())
				return run.snapshot();
			current(run, dispatch.context, dispatch.recipe);
			tx.terminal(run, "FAILED", code, message, "runtime");
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
	private ResolvedApplication resolve(String runId, ValidatedWorkflow workflow) {
		requireSequential(workflow);
		ExecutionCompatibility supplied = requireDeployment();
		ResolvedApplication resolved = new ResolvedApplication(registry, supplied, workflow);
		store.transaction(tx -> {
			compatible(tx.get(runId), workflow, resolved);
			return null;
		});
		return resolved;
	}

	private static void requireSequential(ValidatedWorkflow workflow) {
		Objects.requireNonNull(workflow);
		if (!workflow.capabilities().equals(Set.of(Capability.OPERATION, Capability.TERMINAL)))
			throw new WorkflowRefusal("UNSUPPORTED_CAPABILITY",
					"this runtime admits sequential steps and terminals only");
	}

	private static RunState.Invocation current(RunState run, StepContext context, Binding recipe) {
		if (!Objects.equals(run.node, recipe.placement().graphName()))
			throw new WorkflowRefusal("ATTEMPT_CHANGED", "current graph node differs");
		RunState.Invocation call = findInvocation(run, run.node);
		if (call == null)
			throw new WorkflowRefusal("ATTEMPT_CHANGED", "no unresolved invocation");
		validateInvocation(run, call, recipe);
		if (!call.id.equals(context.invocationId()) || call.attempts.isEmpty()
				|| !call.attempts.getLast().id.equals(context.attemptId()))
			throw new WorkflowRefusal("ATTEMPT_CHANGED", "current execution attempt differs");
		return call;
	}

	private static void validateInvocation(RunState run, RunState.Invocation call, Binding recipe) {
		if (!invocationId(run.id, recipe.placement()).equals(call.id)
				|| !recipe.placement().graphName().equals(call.placement)
				|| !valueId(recipe.input().identity()).equals(call.input)
				|| !valueId(recipe.output().identity()).equals(call.output) || !"UNRESOLVED".equals(call.status))
			throw new WorkflowRefusal("INVOCATION_CHANGED", "saved invocation differs or is already committed");
	}

	static void finish(JdbcRunStore.Tx tx, RunState run, ValidatedWorkflow workflow) throws Exception {
		var node = workflow.graph().nodeByName(run.node);
		if (!(node instanceof WorkflowNode.TerminalNode terminal))
			throw new WorkflowRefusal("CURSOR_CHANGED", "terminal graph node required");
		if (terminal.intent() == Terminal.SUCCEEDED) {
			run.output = valueId(terminal.successValue());
			verifyValue(run.id, run.values.get(run.output), workflow.values().get(terminal.successValue()));
		}
		tx.terminal(run, terminal.intent().name(), "AUTHORED_" + terminal.intent().name(), terminal.reason(),
				"workflow");
	}

	/**
	 * Inside preparation, obtain the validated effective input. Saved inputs are verified
	 * and reused; only a new assembled input is constructed from ordered, verified
	 * component values. Store its exact bytes/provenance before any Step can execute.
	 */
	private void materialize(JdbcRunStore.Tx tx, RunState run, ValueId id, ValidatedWorkflow workflow,
			ResolvedApplication resolved, String producer) throws Exception {
		if (run.values.containsKey(valueId(id))) {
			verifiedValue(tx, run, id, workflow);
			return;
		}
		var recipe = workflow.values().get(id);
		if (recipe == null || recipe.components().isEmpty())
			throw new WorkflowRefusal("VALUE_MISSING", "selected immutable value missing: " + id);
		List<Object> components = new ArrayList<>();
		for (ValueId component : recipe.components()) {
			// Components are already selected historical facts. A missing saved input
			// must never be rebuilt.
			components.add(decode(tx, run, component, workflow, resolved));
		}
		Object input = resolved.assemble(recipe.declaration(), components);
		putValue(run, recipe, resolved.encode(input, recipe.declaration()), producer);
	}

	private Object decode(JdbcRunStore.Tx tx, RunState run, ValueId id, ValidatedWorkflow workflow,
			ResolvedApplication resolved) throws Exception {
		RunState.Value value = verifiedValue(tx, run, id, workflow);
		return resolved.decode(value.payload, workflow.values().get(id).declaration());
	}

	private RunState.Value verifiedValue(JdbcRunStore.Tx tx, RunState run, ValueId id, ValidatedWorkflow workflow) {
		RunState.Value value = run.values.get(valueId(id));
		verifyValue(run.id, value, workflow.values().get(id));
		return value;
	}

	static void verifyValue(String runId, RunState.Value value, ValidatedWorkflow.ValueRecipe recipe) {
		if (value == null || recipe == null)
			throw new WorkflowRefusal("VALUE_MISSING", "committed selected value is missing");
		String producer = recipe.identity().role().equals("root") ? ""
				: invocationId(runId, recipe.identity().placement());
		if (!producer.equals(value.producer) || !value.id.equals(valueId(recipe.identity()))
				|| !value.type.equals(recipe.contract().javaType())
				|| !value.shape.equals(recipe.contract().shapeDigest())
				|| !value.codec.equals(codec(recipe.contract().codec()))
				|| !value.digest.equals(Digests.of(value.payload))
				|| !value.components.equals(recipe.components().stream().map(DurableWorkflows::valueId).toList())
				|| !value.consumed.equals(recipe.consumed().stream().map(DurableWorkflows::valueId).toList()))
			throw new WorkflowRefusal("VALUE_CHANGED", "persisted value identity/type/codec/provenance differs");
	}

	static void putValue(RunState run, ValidatedWorkflow.ValueRecipe recipe, byte[] bytes, String producer) {
		RunState.Value value = new RunState.Value();
		value.id = valueId(recipe.identity());
		value.type = recipe.contract().javaType();
		value.shape = recipe.contract().shapeDigest();
		value.codec = codec(recipe.contract().codec());
		value.digest = Digests.of(bytes);
		value.payload = bytes.clone();
		value.producer = producer;
		value.components = recipe.components().stream().map(DurableWorkflows::valueId).toList();
		value.consumed = recipe.consumed().stream().map(DurableWorkflows::valueId).toList();
		if (run.values.putIfAbsent(value.id, value) != null)
			throw new WorkflowRefusal("VALUE_IMMUTABLE", "cannot overwrite immutable value");
	}

	static String valueId(ValueId id) {
		return Digests.fields(id.placement().graphName(), id.role(), id.phase());
	}

	private static String invocationId(String run, Placement placement) {
		return Digests.fields(run, placement.graphName(), "root");
	}

	private static String codec(TypeContracts.CodecIdentity codec) {
		return Digests.fields(codec.name(), codec.version(), codec.configuration());
	}

	static String compatibility(ValidatedWorkflow workflow, Map<String, String> selections,
			ExecutionCompatibility.Manifest manifest, ExecutionPolicy policy) {
		StringBuilder entries = new StringBuilder();
		new TreeMap<>(selections).forEach((node, name) -> entries.append(Digests.fields(node, name)));
		return Digests.fields("durable-run-v5", workflow.authoredIdentity(), entries.toString(), manifest.identity(),
				codec(workflow.codecIdentity()), policy.identity());
	}

	private void compatible(RunState run, ValidatedWorkflow workflow, ResolvedApplication resolved) {
		ExecutionCompatibility.Manifest supplied = requireDeployment().manifest();
		if (!supplied.equals(run.deployment))
			throw new WorkflowRefusal("COMPATIBILITY",
					"supplied deployment/configuration/codec/runtime differs from admitted manifest");
		if (run.maximumAttempts != policy.maximumAttempts()
				|| !Objects.equals(run.authored, workflow.authoredIdentity())
				|| !Objects.equals(run.selections, resolved.selections())
				|| !run.compatibility.equals(compatibility(workflow, resolved.selections(), supplied, policy)))
			throw new WorkflowRefusal("COMPATIBILITY", "authored behavior, step selection or execution policy differs");
		validateProgress(run, workflow);
	}

	private static RunState.Invocation findInvocation(RunState run, String node) {
		RunState.Invocation found = null;
		for (var call : run.invocations)
			if (Objects.equals(call.placement, node)) {
				if (found != null)
					throw new WorkflowRefusal("INVOCATION_CHANGED", "duplicate saved node invocation");
				found = call;
			}
		return found;
	}

	/**
	 * Verify that saved history is a graph prefix and the cursor is its first uncommitted
	 * node.
	 */
	private static void validateProgress(RunState run, ValidatedWorkflow workflow) {
		var graph = workflow.graph();
		String node = graph.startNode();
		Set<String> seen = new HashSet<>();
		int recorded = 0;
		while (graph.nodeByName(node) instanceof WorkflowNode.StepNode) {
			if (!seen.add(node))
				throw new WorkflowRefusal("CURSOR_CHANGED", "cyclic sequential continuation");
			var call = findInvocation(run, node);
			if (call == null)
				break;
			recorded++;
			Binding binding = graph.binding(node);
			if (!invocationId(run.id, binding.placement()).equals(call.id)
					|| !valueId(binding.input().identity()).equals(call.input)
					|| !valueId(binding.output().identity()).equals(call.output))
				throw new WorkflowRefusal("INVOCATION_CHANGED", "saved graph invocation differs");
			if (!"COMMITTED".equals(call.status))
				break;
			verifyValue(run.id, run.values.get(call.input), workflow.values().get(binding.input().identity()));
			verifyValue(run.id, run.values.get(call.output), workflow.values().get(binding.output().identity()));
			node = graph.unconditionalSuccessor(node);
		}
		if (!Objects.equals(node, run.node) || recorded != run.invocations.size())
			throw new WorkflowRefusal("CURSOR_CHANGED", "saved cursor/history is not the admitted graph continuation");
		if ("SUCCEEDED".equals(run.status) && !(graph.nodeByName(node) instanceof WorkflowNode.TerminalNode))
			throw new WorkflowRefusal("CURSOR_CHANGED", "success requires an explicit terminal node");
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

	private record Dispatch(Binding recipe, Object input, StepContext context) implements Advancement {
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
