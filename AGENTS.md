# Agent Rules

- For repository-level development, debugging, workflow changes, or documentation work on this project, start with [$dataagent-engineering](/Users/xiaoshi/Documents/code/AI/DataAgent/skills/dataagent-engineering/SKILL.md).
- Use `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/README.md` as the documentation system entrypoint.
- Use `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/architecture/codebase-guide.md` as the primary code map.
- Keep `AGENTS.md` short. Put detailed, durable project knowledge in `docs/`, not here.

- Place documents by type:
- Design and technical design docs go under `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/design-docs/`.
- Execution plans go under `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/plans/active/` or `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/plans/completed/`.
- Dated debugging, validation, and performance records go under `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/engineering-notes/`.
- Business model, table structure, metadata, and query-rule references go under `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/domain-reference/`.
- Setup, usage, and contributor-facing manuals go under `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/guides/`.
- Do not create new root-level documentation directories such as `工单表结构说明/`, `ai执行方案/`, or `管网元数据说明/`.

- Read the right documents before editing:
- For Graph workflow, schema recovery, vector persistence, run-page loops, or streaming failures, read `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/engineering-notes/agent6-pipe-network-tuning-notes-2026-03-18.md`.
- For burst-analysis routing, session semantic references, or run-page burst follow-up questions, read `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/engineering-notes/agent6-burst-analysis-engineering-notes-2026-03-24.md`.
- For `agent/6` pipeline or work-order behavior, read `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/domain-reference/agent6-pipeline/` and `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/domain-reference/agent6-workorder/` before changing prompts, workflow logic, or verification steps.

- Documentation maintenance is part of the change:
- If a change affects long-lived behavior, code entrypoints, workflow expectations, or debugging steps, update the relevant long-lived doc in the same change.
- Do not leave stable rules only inside dated notes, chat transcripts, or ad hoc plans.

- When the user asks to start, restart, or verify the local DataAgent development environment, use [$start-data-agent](/Users/xiaoshi/Documents/code/AI/DataAgent/skills/start-data-agent/SKILL.md).
- The default startup entrypoint is `/Users/xiaoshi/Documents/code/AI/DataAgent/scripts/start-data-agent.sh`.
- Do not replace the startup script with ad hoc `mvn`, `java -jar`, or `npm` startup commands unless the task is explicitly to debug or change the startup flow itself.
- Prefer the real MySQL-backed startup flow and the backend configuration in `/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/resources/application.yml`.
- Do not switch to H2 unless the user explicitly asks for an H2-based run.
- If prompt templates under `/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/resources/prompts/` or workflow nodes under `/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/` changed, do not validate behavior against a stale backend process.
