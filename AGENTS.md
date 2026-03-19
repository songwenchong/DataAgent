# Agent Rules

- For repository-level development, debugging, documentation updates, workflow changes, or feature work on this project, start with [$dataagent-engineering](/Users/xiaoshi/Documents/code/AI/DataAgent/skills/dataagent-engineering/SKILL.md).
- Use `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/CODEBASE_GUIDE.md` as the primary code map.
- Use `/Users/xiaoshi/Documents/code/AI/DataAgent/docs/agent6-pipe-network-tuning-notes-2026-03-18.md` when the task touches Graph workflow, schema recovery, vector persistence, run-page loops, or streaming failures.
- When the user asks to start, restart, or verify the local DataAgent development environment, use [$start-data-agent](/Users/xiaoshi/Documents/code/AI/DataAgent/skills/start-data-agent/SKILL.md).
- The default startup entrypoint is `/Users/xiaoshi/Documents/code/AI/DataAgent/scripts/start-data-agent.sh`.
- Do not replace the startup script with ad hoc `mvn`, `java -jar`, or `npm` startup commands unless the task is explicitly to debug or change the startup flow itself.
- Prefer the real MySQL-backed startup flow and the backend configuration in `/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/resources/application.yml`.
- Do not switch to H2 unless the user explicitly asks for an H2-based run.
- If prompt templates under `/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/resources/prompts/` or workflow nodes under `/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/` changed, do not validate behavior against a stale backend process.
