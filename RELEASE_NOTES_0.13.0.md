# Agent Workflow 0.13.0 — first executable-DSL milestone

Prepared release candidate; not yet published. Requires Java 21. Maven entry: `io.github.markpollack:workflow-batch:0.13.0`. Required Agent Judge core dependency is `0.18.0`; native routing also needs its optional `agent-judge-json-jackson2` artifact. Final publication waits for these stable dependencies on Central.

## Supported

- Ordinary constructor-injected `Step<I,O>` objects; compiler-derived typed bindings and earlier-value captures. Business data is passed as arguments/results; `StepContext` holds metadata.
- Nested reusable workflows as isolated scopes in one run.
- Exhaustive enum decisions and native Verdict PASS, FAIL, INCONCLUSIVE, NOT_APPLICABLE routes. Complete assessments, exact inputs and selected routes commit together; accepted evaluations are reused during recovery.
- Static parallel groups with full settlement.
- Runtime `forEach` with explicit positive `maxItems` and `maxInFlight`. A saved indexed manifest gives equal input occurrences distinct identities. Item state is isolated; results keep original input order. Empty input produces an empty typed list. Oversize input refuses before item effects. Logical capacity counts unsettled whole item workflows, independently of physical Step capacity, including nested work at capacity one.
- Local embedded JDBC saved progress and process-crash recovery. Completed results remain committed; only unresolved work repeats within the saved attempt/deadline bounds. The application explicitly resumes unfinished runs.

The [deterministic PR-review-style example](examples/review-files/README.md) compiles and executes without a provider or API key. It discovers files, reviews each file and passes ordered typed results plus the original request to a report Step. A negative review remains a business value; execution failure prevents a successful join.

## Breaking changes and recovery limits

Store/run format **9** refuses older, incomplete and unknown formats before writable initialization. There is **no migration or automatic conversion**. Keep old data with its old deployment; start new stores for this release. Definition, registrations, build/configuration, codec, JDK and execution-policy compatibility must match saved runs. Updating dependencies or source and reusing a build ID is outside that contract.

One local JVM owns a store. No live-owner takeover, remote workers or background scheduler. Cancellation/expiry fences result acceptance but cannot forcibly stop application calls. Unresolved external effects may repeat after death; use stable invocation IDs for external idempotency. Known execution failures stop their scope; full group settlement precedes failure propagation. Terminal failed/cancelled runs remain terminal; saved-progress restart after terminal failure is not provided. Fresh reruns may repeat all effects. Data/events are retained without a pruning API. JVM-kill evidence does not prove power-loss durability.

## Deferred

Durable loops, waits/timers, Workflow committed-event Journal projection, first-class non-routing evaluation, visualization/AI authoring and broader hardening follow this milestone. Their model types do not make them executable: validation refuses unsupported runtime constructs. The fluent grammar does not yet represent a parent whose only final operation is a non-returning child; the shared programmatic compiler supports that shape.

Legacy `Blueprint`/`GraphCompositionStrategy`, agent-loop strategies and Journal tracing remain in companion modules for existing users. They are separate from `Workflows` → `ValidatedWorkflow` → `DurableWorkflows`, and do not gain this milestone's persistence, isolation or recovery semantics. The old `Workflow`/`WorkflowExecutor`, StepRunner/checkpoint adapters and `workflow-temporal` are absent from this reactor; their old tutorials target prior releases. Broader legacy retirement and consumer migrations are deferred; existing consumers can stay pinned to their demonstrated deployments.

## Packaging

Publishable coordinates use `io.github.markpollack` at `0.13.0`: `agent-workflow-parent` (POM), `workflow-api`, `workflow-core`, `workflow-tools`, `workflow-agents`, `workflow-flows`, `workflow-batch`, `workflow-journal`. `workflow-examples` builds but is excluded from Central. Main, source and Javadoc archives include the root BSL license; existing third-party notices are retained. Jackson families are patched to 2.22.3 / 3.2.3. Publication uses the release manager's certified consumer-rooted bundle, not a reactor-generated publication SBOM.

Maven Central versions cannot be replaced or unpublished. A release correction requires a new version. Rolling application binaries back does not migrate new stores to older formats.
