---
name: dataagent-engineering
description: Use when working on the DataAgent codebase in /Users/xiaoshi/Documents/code/AI/DataAgent, especially for feature development, workflow changes, schema/vector recovery, knowledge configuration, run-page debugging, or documenting engineering decisions. Start from the project code map and the agent6 tuning notes before changing Graph, prompts, startup recovery, or streaming behavior.
---

# DataAgent Engineering

Use this skill for repository-level engineering work on DataAgent.

## Workflow

1. Start with `docs/README.md` to understand the document layout, then use `docs/architecture/codebase-guide.md` to locate the real code entry points.
2. If the work touches run-page behavior, Graph workflow, schema recovery, vector persistence, or streaming failures, also read `docs/engineering-notes/agent6-pipe-network-tuning-notes-2026-03-18.md`.
3. If the work touches burst-analysis routing, multi-turn result references, run-page burst debugging, or session/thread context isolation, also read `docs/engineering-notes/agent6-burst-analysis-engineering-notes-2026-03-24.md`.
4. If the work touches `agent/6` pipeline metadata, work orders, or query rules, also read:
   - `docs/domain-reference/agent6-pipeline/`
   - `docs/domain-reference/agent6-workorder/`
5. Map the task to the correct layer before editing:
   - backend workflow: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/`
   - backend graph/service wiring: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/` and `.../service/graph/`
   - prompt changes: `data-agent-management/src/main/resources/prompts/`
   - frontend run/config pages: `data-agent-frontend/src/views/` and `data-agent-frontend/src/components/`
   - vector/schema recovery: `.../service/vectorstore/`, `.../service/schema/`, `.../service/agent/AgentStartupInitialization.java`
6. When changing Graph behavior, inspect both the Node and its Dispatcher. Do not change only one side.
7. When changing knowledge recall or schema initialization, verify both:
   - relational configuration state
   - vector document recovery and persistence
8. Prefer updating project docs together with code when the change affects workflow, startup behavior, debugging steps, or long-lived domain rules.

## Default Reading Order

1. `README.md`
2. `docs/README.md`
3. `docs/architecture/architecture.md`
4. `docs/guides/developer-guide.md`
5. `docs/architecture/codebase-guide.md`
6. `docs/domain-reference/agent6-pipeline/` or `docs/domain-reference/agent6-workorder/` when the issue depends on domain facts
7. `docs/engineering-notes/agent6-pipe-network-tuning-notes-2026-03-18.md` when the issue smells like startup/schema/vector/streaming
8. `docs/engineering-notes/agent6-burst-analysis-engineering-notes-2026-03-24.md` when the issue smells like burst branch routing, session semantic references, or run-page burst follow-up questions
9. Relevant backend/frontend entry files from that guide

## Burst Analysis And Session Semantics

Read `docs/engineering-notes/agent6-burst-analysis-engineering-notes-2026-03-24.md` before changing burst analysis, follow-up result references, or run-page verification for `agent/6`.

When the task touches burst routing or multi-turn pipe references, prioritize checking:

- `workflow/node/IntentRecognitionNode`
- `workflow/node/ReferenceResolutionNode`
- `workflow/node/BurstAnalysisNode`
- `service/burst/BurstAnalysisServiceImpl`
- `service/graph/Context/*`
- `data-agent-frontend/src/views/AgentRun.vue`

Keep one rule fixed unless the user explicitly wants a behavioral change:

- Text context is only for LLM understanding.
- Structured session-level reference targets are the only safe source for burst API parameters.

## High-Risk Areas

- `DataAgentConfiguration.java`
- `GraphServiceImpl.java`
- `workflow/node/*`
- `workflow/dispatcher/*`
- `service/burst/BurstAnalysisServiceImpl.java`
- `service/graph/Context/*`
- `AgentStartupInitialization.java`
- `AgentVectorStoreServiceImpl.java`
- `SchemaServiceImpl.java`
- `data-agent-frontend/src/services/graph.ts`
- `data-agent-frontend/src/views/AgentRun.vue`

## Project-Specific Rules

- Do not assume “configuration exists in DB” means “runtime recall works”; vector recovery may still be broken.
- For looping plans, inspect `PlannerNode`, `PlanExecutorNode`, `SqlGenerateNode`, and `SemanticConsistencyNode` together.
- For “stream connection failed”, verify whether the failure is frontend SSE handling, backend stream handling, or upstream LLM connection timeout.
- For burst-analysis follow-up failures, first rule out a stale backend process before changing prompts or routing logic.
- Do not let `gid/layerId` leak into `MULTI_TURN_CONTEXT` or other prompt-facing summaries; burst parameter resolution must use structured session context, not text regex over old summaries.
- For burst target resolution, preserve the invariant: filter by semantic attributes first, then apply ordinal within the filtered result set, and keep ambiguity clarification when multiple candidates remain.
- New sessions must start from empty semantic reference context; never let a fresh session reuse another session's burst/query candidates.
- For startup or restart requests, use [$start-data-agent](/Users/xiaoshi/Documents/code/AI/DataAgent/skills/start-data-agent/SKILL.md).
