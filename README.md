# Agent Workflow

A Java 21 library for typed Steps, reusable workflows, exhaustive decisions and bounded concurrent work, with local JDBC durability and recovery.

[Documentation](https://lab.pollack.ai/projects/agent-workflow) · [0.13.0 release notes](RELEASE_NOTES_0.13.0.md) · [Compiling deterministic example](examples/review-files/README.md) · [Six runnable lessons](https://github.com/markpollack/workflow-dsl-examples/tree/tutorial/workflow-dsl-consolidation-20261008/executable-dsl)

## Maven

The prepared first executable-DSL milestone is **0.13.0**, pending publication. Use its staged artifacts until Maven Central publication is verified:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>workflow-batch</artifactId>
    <version>0.13.0</version>
</dependency>
```

Native Verdict routing additionally requires `io.github.markpollack:agent-judge-json-jackson2:0.18.0`.

## Build

```bash
./mvnw clean verify
```

Supports typed sequential Steps and derived bindings, nested reusable workflows, exhaustive enum/native Verdict decisions, static parallel composition and bounded runtime fan-out. Durable loops, waits/timers and Workflow committed-event Journal projection are deferred. This milestone does not complete the full R1 roadmap.

Store format 9 refuses older formats; no automatic migration. Recovery requires the exact compatible deployment and reuses committed work. Unresolved external effects can repeat; terminal failed runs cannot be reopened. See the [runtime guide](workflow-batch/README-durable.md) for bounds and lifecycle limitations.

## Consolidation review branch

This branch adds `Workflows.Sequence.build()` for a final child that always fails or cancels.
It is an additive authoring correction for a subsequent, unselected version and is **not in 0.13.0**.
Ordinary returning paths still require explicit terminals; unreachable successors still refuse.
The six-lesson progression uses only 0.13.0 APIs.

## License

[Business Source License 1.1](LICENSE)
