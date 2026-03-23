---
name: save-analysis-plan-md
description: Save a user's request, analysis, conclusions, and phased execution plan into a Markdown report by using the bundled save script. Use when Codex should both answer the user and persist a reusable task record, especially for problem analysis, solution design, debugging conclusions, or implementation plans that should be archived as .md files in the configured desktop archive folder.
---

# Save Analysis Plan Md

Follow this workflow whenever the skill is active.

## Workflow

1. Restate the request in 1-3 sentences so the saved file is understandable on its own.
2. Analyze the problem and extract the important constraints, risks, and assumptions.
3. Produce a clear conclusion section that states the recommended direction or final answer.
4. Convert the solution into an execution plan split into phases. Use at least 2 phases when the task is not trivial.
5. Save the record as Markdown by running [scripts/save-report.ps1](./scripts/save-report.ps1).

## Required Output Structure

Use the structure and headings from [references/report-template.md](./references/report-template.md).

Keep the execution plan phase-based:

- Give each phase a goal.
- List concrete actions inside each phase.
- Add an expected outcome or completion signal for each phase.

## Saving Rules

- Use the default output directory from the script unless the user explicitly asks for another location.
- Use a concise Chinese or English title derived from the request.
- Save one Markdown file per user request.
- Include a timestamp in the filename by using the script defaults unless the user asks for a custom filename.
- Preserve UTF-8 content.

## Command

Prepare four UTF-8 text files in the current workspace for:

- request summary
- analysis
- conclusion
- phased plan

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\save-report.ps1 `
  -Title "<title>" `
  -RequestFile "<path-to-request-file>" `
  -AnalysisFile "<path-to-analysis-file>" `
  -ConclusionFile "<path-to-conclusion-file>" `
  -PlanFile "<path-to-plan-file>"
```

Override `-OutputDir` only if the user asks to save somewhere else.

## Notes

- If writing to the desktop folder requires approval in the current sandbox, request approval and then save the file.
- If the task is very small, keep the analysis concise, but still include all required sections.
- If the user only wants the document and not a chat reply, keep the chat response brief and point to the saved file.
