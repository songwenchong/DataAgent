#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$PROJECT_ROOT/.run/data-agent/frontend.pid"

mkdir -p "$PROJECT_ROOT/.run/data-agent"

printf '[data-agent-frontend] Starting frontend from %s\n' "$PROJECT_ROOT"
printf '%s\n' "$$" >"$PID_FILE"

cd "$PROJECT_ROOT/data-agent-frontend"
exec npm run dev
