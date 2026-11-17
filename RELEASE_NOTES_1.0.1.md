# Agent Workflow 1.0.1 — draft

Draft notes for the next patch release; the version is not yet set. Requires Java 21.

## Security

- **CVE-2026-75140** (HIGH): denial of service in `org.jsoup:jsoup` from deeply nested documents, fixed in jsoup 1.23.2. Also **CVE-2026-71497** (MEDIUM), fixed in jsoup 1.23.1. `workflow-agents` reached jsoup 1.15.4 through `spring-ai-agent-utils` 0.10.0 and `flexmark-html2md-converter` 0.64.8, which `SmartWebFetchTool` uses to convert fetched pages to Markdown. `workflow-agents` now declares jsoup **1.24.1** directly, so applications resolve the patched version, and the published POM's floor check enforces at least 1.23.2.

HTML-to-Markdown conversion is unchanged: a new offline test converts a representative page and checks the result is byte-identical to the output on jsoup 1.15.4.
