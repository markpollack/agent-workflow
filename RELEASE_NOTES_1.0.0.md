# Agent Workflow 1.0.0

First 1.x release, for the AgentWorks BOM 1.22.0. Requires Java 21 and Agent Judge 1.0.0 (`agent-judge-core`; native Verdict routing also uses `agent-judge-json-jackson2` 1.0.0).

## Breaking changes

The durable engine (`workflow-api`, `workflow-flows`, `workflow-batch`, `workflow-journal`) no longer brings Spring AI or the legacy loop patterns (`workflow-core`) onto an application's classpath. Three types moved, and each now lives in a package that only its own JAR contains:

| Type | Before | Now | JAR |
|---|---|---|---|
| `NodeType` | `io.github.markpollack.workflow.patterns.graph.NodeType` (workflow-core) | `io.github.markpollack.workflow.core.NodeType` | workflow-api |
| `LoopPattern` | `io.github.markpollack.workflow.core.LoopPattern` (workflow-api) | `io.github.markpollack.workflow.patterns.LoopPattern` | workflow-core |
| `ToolCallListener` | `io.github.markpollack.workflow.core.ToolCallListener` (workflow-api) | `io.github.markpollack.workflow.patterns.ToolCallListener` | workflow-core |

Update the imports; the types themselves are unchanged. Code that implements `LoopPattern` or `ToolCallListener` now needs `workflow-core`.

`workflow-flows` declares Spring AI as optional. An application that uses `ChatClientStep` adds `org.springframework.ai:spring-ai-client-chat` itself.

## New modules

Framework integrations, one per framework, each providing the durable workflow beans from `agent-workflow.*` properties (see the README):

- `workflow-spring-boot-autoconfigure` (Spring Boot 4.1.1)
- `workflow-quarkus` and `workflow-quarkus-deployment` (Quarkus 3.40.1)
- `workflow-micronaut` (Micronaut platform 4.10.20, core 4.10.30)

Each imports the Jackson BOMs ahead of its platform BOM, so it resolves Jackson 2.22.3 and 3.2.3 or later.

## Removed

`workflow-temporal` is no longer published and has left the Agentworks BOM. Its last release, 0.12.3, calls a Workflow class that 0.14 no longer has; `workflow-batch` is the durable runtime. A Temporal binding for the current engine may return in a later release.

## Dependencies

- Agent Judge 0.18.0 → 1.0.0, with no source changes.
- No Agent Client dependency at any scope.
- A release build refuses snapshot dependencies, and published POMs name no repository.
