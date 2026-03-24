---
name: start-data-agent
description: Start, restart, or verify the local DataAgent development environment from the repository root by running ./scripts/start-data-agent.sh. Use when the user asks to boot, reboot, or verify the frontend and backend with the real MySQL-backed configuration instead of H2 in a non-Windows environment.
---

# Start Data Agent

## Workflow

1. Only use this skill in a non-Windows environment. Do not use this skill as the default startup path on Windows.
2. Work from the repository root.
3. Use the repository script as the only default startup entrypoint:
   - `./scripts/start-data-agent.sh`
4. The script is responsible for:
   - using the real backend config in `data-agent-management/src/main/resources/application.yml`
   - avoiding H2 unless explicitly requested
   - checking health on `8065` and `3000`
   - restarting managed backend/frontend processes when needed
   - handling prompt/workflow fingerprint changes before reuse
5. If startup fails because the sandbox cannot expose local ports or launch terminal windows, rerun the same script with escalation instead of replacing it with ad hoc `mvn` or `npm` commands.
6. After the script completes, verify:
   - `http://127.0.0.1:8065/v3/api-docs`
   - `http://127.0.0.1:3000/`
7. Report the two local URLs and note whether the script reused healthy services or restarted them.

## Commands

```bash
./scripts/start-data-agent.sh
```

## Checks

- Backend health endpoint returns `200`.
- Frontend home page returns `200`.
- The managed startup script completed without error.
- The backend is using the real config from `data-agent-management/src/main/resources/application.yml`.

## Notes

- Do not bypass the script with manual `mvn`, `java -jar`, or `npm run dev` commands unless the task is specifically to debug or change the startup flow itself.
- If the user asks only for verification, the same script is still the preferred entrypoint because it can reuse healthy managed processes.
- Windows is out of scope for this skill. Do not try to apply this skill verbatim on Windows.
- The current startup flow depends on Unix-style shell execution and local macOS terminal automation via `osascript`; if that macOS-specific step is blocked, request escalation and rerun the same script.
