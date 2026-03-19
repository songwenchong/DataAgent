#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/data-agent-management"
FRONTEND_DIR="$PROJECT_ROOT/data-agent-frontend"
STATE_DIR="$PROJECT_ROOT/.run/data-agent"
LOG_DIR="$STATE_DIR/logs"

BACKEND_URL="http://127.0.0.1:8065/v3/api-docs"
FRONTEND_URL="http://127.0.0.1:3000/"
BACKEND_HTML_TITLE="DataAgent Backend API"
FRONTEND_HTML_TITLE="Spring AI Alibaba Data Agent Web UI"

BACKEND_PID_FILE="$STATE_DIR/backend.pid"
FRONTEND_PID_FILE="$STATE_DIR/frontend.pid"
BACKEND_FINGERPRINT_FILE="$STATE_DIR/backend.fingerprint"
FRONTEND_INSTALL_LOG_FILE="$LOG_DIR/frontend-install.log"

mkdir -p "$STATE_DIR" "$LOG_DIR"

log() {
  printf '[start-data-agent] %s\n' "$*"
}

fail() {
  printf '[start-data-agent] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "Missing required command: $1"
  fi
}

check_backend_build_prerequisites() {
  git_state="$(git rev-parse --is-inside-work-tree 2>/dev/null || printf 'false')"
  if [ "$git_state" != "true" ]; then
    fail "Current project is not a Git work tree. Backend startup runs Spotless with ratchetFrom origin/main in pom.xml, so .git must be available from this directory."
  fi

  if ! git rev-parse --verify origin/main >/dev/null 2>&1; then
    remotes="$(git remote 2>/dev/null | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
    if [ -n "$remotes" ]; then
      fail "Git ref origin/main is missing. Backend startup runs Spotless with ratchetFrom origin/main in pom.xml. Current remotes: $remotes"
    fi
    fail "Git ref origin/main is missing. Backend startup runs Spotless with ratchetFrom origin/main in pom.xml."
  fi
}

read_file_trimmed() {
  if [ -f "$1" ]; then
    tr -d '[:space:]' <"$1"
  fi
}

pid_is_running() {
  pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

stop_pid() {
  pid="$1"
  if ! pid_is_running "$pid"; then
    return 0
  fi

  kill "$pid" 2>/dev/null || true

  i=1
  while [ "$i" -le 10 ]; do
    if ! pid_is_running "$pid"; then
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done

  kill -9 "$pid" 2>/dev/null || true
}

http_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$1" 2>/dev/null || printf '000'
}

is_healthy() {
  [ "$(http_code "$1")" = "200" ]
}

wait_for_http() {
  url="$1"
  attempts="$2"
  i=1
  while [ "$i" -le "$attempts" ]; do
    if is_healthy "$url"; then
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  return 1
}

url_contains() {
  url="$1"
  expected="$2"
  content="$(curl -sS "$url" 2>/dev/null || true)"
  case "$content" in
    *"$expected"*) return 0 ;;
    *) return 1 ;;
  esac
}

find_listener_pid() {
  lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

stop_listener_on_port() {
  port="$1"
  pid="$(find_listener_pid "$port")"
  if [ -n "$pid" ]; then
    log "Stopping listener on port $port (pid=$pid)."
    stop_pid "$pid"
  fi
}

shell_quote() {
  printf "%s" "$1" | sed "s/'/'\\\\''/g; 1s/^/'/; \$s/\$/'/"
}

open_terminal_window() {
  label="$1"
  runner_path="$2"
  runner_quoted="$(shell_quote "$runner_path")"
  project_root_quoted="$(shell_quote "$PROJECT_ROOT")"
  label_quoted="$(shell_quote "$label")"
  command="printf '\\033]0;%s\\007' $label_quoted; cd $project_root_quoted && sh $runner_quoted; status=\$?; printf '\\n[%s] exited with status %s\\n' $label_quoted \"\$status\"; exec \"\${SHELL:-/bin/zsh}\" -l"

  osascript - "$command" <<'APPLESCRIPT'
on run argv
  set commandText to item 1 of argv
  tell application "Terminal"
    activate
    do script commandText
  end tell
end run
APPLESCRIPT
}

backend_fingerprint() {
  (
    cd "$PROJECT_ROOT"
    find \
      data-agent-management/src/main/resources/prompts \
      data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node \
      -type f -print 2>/dev/null \
      | LC_ALL=C sort \
      | while IFS= read -r file; do
          printf 'FILE:%s\n' "$file"
          cat "$file"
          printf '\n'
        done
  ) | shasum | awk '{print $1}'
}

ensure_frontend_dependencies() {
  if [ -d "$FRONTEND_DIR/node_modules" ]; then
    return 0
  fi

  log "Frontend dependencies are missing. Running npm install."
  : >"$FRONTEND_INSTALL_LOG_FILE"
  (
    cd "$FRONTEND_DIR"
    npm install >>"$FRONTEND_INSTALL_LOG_FILE" 2>&1
  ) || {
    tail -n 40 "$FRONTEND_INSTALL_LOG_FILE" >&2 || true
    fail "npm install failed for the frontend."
  }
}

start_backend() {
  current_fingerprint="$(backend_fingerprint)"
  previous_fingerprint="$(read_file_trimmed "$BACKEND_FINGERPRINT_FILE" || true)"
  recorded_pid="$(read_file_trimmed "$BACKEND_PID_FILE" || true)"

  log "Backend will use $BACKEND_DIR/src/main/resources/application.yml."

  if is_healthy "$BACKEND_URL" && [ -n "$previous_fingerprint" ] \
    && [ "$current_fingerprint" = "$previous_fingerprint" ] \
    && pid_is_running "$recorded_pid"; then
    log "Backend is already healthy and running in its managed terminal."
    return 0
  fi

  if is_healthy "$BACKEND_URL"; then
    if ! url_contains "$BACKEND_URL" "$BACKEND_HTML_TITLE"; then
      fail "Port 8065 is occupied by a healthy service that is not the DataAgent backend."
    fi
    log "Backend restart is required to attach it to a managed terminal."
    stop_listener_on_port 8065
  else
    if [ -n "$(find_listener_pid 8065)" ]; then
      if pid_is_running "$recorded_pid"; then
        stop_listener_on_port 8065
      else
        fail "Port 8065 is occupied by another process and the backend health check failed."
      fi
    fi
    log "Backend is not healthy. Starting it in a dedicated terminal."
  fi

  check_backend_build_prerequisites

  rm -f "$BACKEND_PID_FILE"
  open_terminal_window "DataAgent Backend" "$PROJECT_ROOT/scripts/run-data-agent-backend.sh"

  if ! wait_for_http "$BACKEND_URL" 120; then
    fail "Backend did not become healthy at $BACKEND_URL."
  fi

  printf '%s\n' "$current_fingerprint" >"$BACKEND_FINGERPRINT_FILE"
  log "Backend is healthy: $BACKEND_URL"
}

start_frontend() {
  recorded_pid="$(read_file_trimmed "$FRONTEND_PID_FILE" || true)"

  ensure_frontend_dependencies

  if is_healthy "$FRONTEND_URL" && pid_is_running "$recorded_pid"; then
    log "Frontend is already healthy and running in its managed terminal."
    return 0
  fi

  if is_healthy "$FRONTEND_URL"; then
    if ! url_contains "$FRONTEND_URL" "$FRONTEND_HTML_TITLE"; then
      fail "Port 3000 is occupied by a healthy service that is not the DataAgent frontend."
    fi
    log "Frontend restart is required to attach it to a managed terminal."
    stop_listener_on_port 3000
  else
    if [ -n "$(find_listener_pid 3000)" ]; then
      if pid_is_running "$recorded_pid"; then
        stop_listener_on_port 3000
      else
        fail "Port 3000 is occupied by another process and the frontend health check failed."
      fi
    fi
    log "Frontend is not healthy. Starting it in a dedicated terminal."
  fi

  rm -f "$FRONTEND_PID_FILE"
  open_terminal_window "DataAgent Frontend" "$PROJECT_ROOT/scripts/run-data-agent-frontend.sh"

  if ! wait_for_http "$FRONTEND_URL" 60; then
    fail "Frontend did not become healthy at $FRONTEND_URL."
  fi

  log "Frontend is healthy: $FRONTEND_URL"
}

main() {
  require_cmd curl
  require_cmd git
  require_cmd lsof
  require_cmd npm
  require_cmd osascript
  require_cmd shasum

  if [ ! -f "$BACKEND_DIR/src/main/resources/application.yml" ]; then
    fail "Backend application.yml was not found."
  fi

  start_backend
  start_frontend

  log "DataAgent is ready."
  log "Backend:  $BACKEND_URL"
  log "Frontend: $FRONTEND_URL"
}

main "$@"
