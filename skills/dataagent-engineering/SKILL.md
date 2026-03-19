---
name: dataagent-engineering
description: Use when working on the DataAgent codebase in /Users/xiaoshi/Documents/code/AI/DataAgent, especially for feature development, workflow changes, schema/vector recovery, knowledge configuration, run-page debugging, or documenting engineering decisions. Start from the project code map and the agent6 tuning notes before changing Graph, prompts, startup recovery, or streaming behavior.
---

# DataAgent Engineering

Use this skill for repository-level engineering work on DataAgent.

## Workflow

1. Start with `docs/CODEBASE_GUIDE.md` to locate the real code entry points.
2. If the work touches run-page behavior, Graph workflow, schema recovery, vector persistence, or streaming failures, also read `docs/agent6-pipe-network-tuning-notes-2026-03-18.md`.
3. Map the task to the correct layer before editing:
   - backend workflow: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/`
   - backend graph/service wiring: `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/` and `.../service/graph/`
   - prompt changes: `data-agent-management/src/main/resources/prompts/`
   - frontend run/config pages: `data-agent-frontend/src/views/` and `data-agent-frontend/src/components/`
   - vector/schema recovery: `.../service/vectorstore/`, `.../service/schema/`, `.../service/agent/AgentStartupInitialization.java`
4. When changing Graph behavior, inspect both the Node and its Dispatcher. Do not change only one side.
5. When changing knowledge recall or schema initialization, verify both:
   - relational configuration state
   - vector document recovery and persistence
6. Prefer updating project docs together with code when the change affects workflow, startup behavior, or debugging steps.

## Default Reading Order

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DEVELOPER_GUIDE.md`
4. `docs/CODEBASE_GUIDE.md`
5. Relevant backend/frontend entry files from that guide

## High-Risk Areas

- `DataAgentConfiguration.java`
- `GraphServiceImpl.java`
- `workflow/node/*`
- `workflow/dispatcher/*`
- `AgentStartupInitialization.java`
- `AgentVectorStoreServiceImpl.java`
- `SchemaServiceImpl.java`
- `data-agent-frontend/src/services/graph.ts`
- `data-agent-frontend/src/views/AgentRun.vue`

## Project-Specific Rules

- Do not assume “configuration exists in DB” means “runtime recall works”; vector recovery may still be broken.
- For looping plans, inspect `PlannerNode`, `PlanExecutorNode`, `SqlGenerateNode`, and `SemanticConsistencyNode` together.
- For “stream connection failed”, verify whether the failure is frontend SSE handling, backend stream handling, or upstream LLM connection timeout.
- For startup or restart requests, use [$start-data-agent](/Users/xiaoshi/Documents/code/AI/DataAgent/skills/start-data-agent/SKILL.md).
