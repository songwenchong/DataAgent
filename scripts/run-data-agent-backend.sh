#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$PROJECT_ROOT/.run/data-agent/backend.pid"

mkdir -p "$PROJECT_ROOT/.run/data-agent"

printf '[data-agent-backend] Starting backend from %s\n' "$PROJECT_ROOT"
printf '%s\n' "$$" >"$PID_FILE"

cd "$PROJECT_ROOT"
exec ./scripts/mvn-local.sh -pl data-agent-management spring-boot:run
