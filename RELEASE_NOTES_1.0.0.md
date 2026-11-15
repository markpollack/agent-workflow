# Agent Workflow 1.0.0 — draft

Draft notes for the next release; the version is not yet set. Requires Java 21.

## Breaking changes

The durable engine (`workflow-api`, `workflow-flows`, `workflow-batch`, `workflow-journal`) no longer brings Spring AI or the legacy loop patterns (`workflow-core`) onto an application's classpath. Three types moved, and each now lives in a package that only its own JAR contains:

| Type | Before | Now | JAR |
|---|---|---|---|
| `NodeType` | `io.github.markpollack.workflow.patterns.graph.NodeType` (workflow-core) | `io.github.markpollack.workflow.core.NodeType` | workflow-api |
| `LoopPattern` | `io.github.markpollack.workflow.core.LoopPattern` (workflow-api) | `io.github.markpollack.workflow.patterns.LoopPattern` | workflow-core |
| `ToolCallListener` | `io.github.markpollack.workflow.core.ToolCallListener` (workflow-api) | `io.github.markpollack.workflow.patterns.ToolCallListener` | workflow-core |

Update the imports; the types themselves are unchanged. Code that implements `LoopPattern` or `ToolCallListener` now needs `workflow-core`.

`workflow-flows` declares Spring AI as optional. An application that uses `ChatClientStep` adds `org.springframework.ai:spring-ai-client-chat` itself.
