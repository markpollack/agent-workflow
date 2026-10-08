# Agent Workflow 0.14.0 — fluent DSL consolidation

Requires Java 21. Maven entry: `io.github.markpollack:workflow-batch:0.14.0`. Native Verdict routing additionally uses `io.github.markpollack:agent-judge-json-jackson2:0.18.0`.

## Changes since 0.13.0

- Add `Workflows.Sequence.build()` for a parent whose final child always fails or cancels. The child's explicit terminal closes the parent path; no unreachable success terminal is needed. Ordinary returning paths still require an explicit terminal, and unreachable successors are refused.
- Improve diagnostics for incomplete or duplicate decision arms, ambiguous typed input bindings, inaccessible sibling values, invalid bounds and reused builder stages. Errors identify the authored workflow/member/arm location and describe the correction.
- Improve public Javadocs and the typed Java authoring tutorial. Six runnable deterministic examples introduce sequences, nested workflows, enum and native Verdict decisions, parallel assessments and ordered runtime fan-out. No provider or API key is required.

```java
var reject = Workflows.define("reject")
    .then(review).terminate(FAILED, "business rejection").build();
var parent = Workflows.define("parent").subWorkflow("review", reject).build();
```

Use ordinary constructor-injected `Step<I,O>` instances. Their declared input records state which earlier typed values they need; the compiler derives the bindings. Keep workflow definition, Step registration and runtime configuration separate. See [the six-lesson tutorial](https://lab.pollack.ai/docs/agent-workflow/tutorial) and [the compiling file-review example](examples/review-files/README.md).

## Supported capabilities and limits

Typed sequential Steps and derived bindings, reusable nested workflows, exhaustive enum and native Verdict routing, static parallel groups, bounded runtime fan-out and one-local-owner JDBC durability/recovery remain supported. This release does not add a runtime, scheduling or persistence model. Fan-out manifests and committed item results are reused during recovery; results retain input order. Negative assessments remain values, while execution failure prevents successful joins with missing results. Logical whole-item bounds remain separate from physical Step capacity.

Durable loops and waits/timers remain unsupported. Workflow Journal projection, first-class non-routing evaluation, visualization/AI authoring and broader hardening remain deferred.

Store/run format remains **9**. Older, unknown or incomplete formats are refused, with no migration or downgrade. Recovery requires the exact compatible deployment; an application upgrade must not reuse a deployment identity or reopen incompatible saved runs. Unresolved external effects can repeat after a crash within saved attempt/deadline allowances. Terminal failed/cancelled runs cannot be reopened from saved progress. One local JVM owns a store; no remote-worker or live-owner takeover support.

## Publication and rollback

Publishes `agent-workflow-parent` (POM), `workflow-api`, `workflow-core`, `workflow-tools`, `workflow-agents`, `workflow-flows`, `workflow-batch` and `workflow-journal` under `io.github.markpollack` at 0.14.0. `workflow-examples` is excluded from Central. Archives retain the BSL license and existing notices. Publication uses the release manager's certified consumer-rooted bundle.

Central versions cannot be replaced or unpublished. Corrections require another version. Rolling binaries back does not migrate stores or make incompatible deployment identities interchangeable.
