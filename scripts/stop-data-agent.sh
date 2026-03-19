#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
STATE_DIR="$PROJECT_ROOT/.run/data-agent"

BACKEND_URL="http://127.0.0.1:8065/v3/api-docs"
FRONTEND_URL="http://127.0.0.1:3000/"
BACKEND_HTML_TITLE="DataAgent Backend API"
FRONTEND_HTML_TITLE="Spring AI Alibaba Data Agent Web UI"

BACKEND_PID_FILE="$STATE_DIR/backend.pid"
FRONTEND_PID_FILE="$STATE_DIR/frontend.pid"

log() {
  printf '[stop-data-agent] %s\n' "$*"
}

fail() {
  printf '[stop-data-agent] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "Missing required command: $1"
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

find_listener_pid() {
  lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
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

stop_managed_service() {
  name="$1"
  pid_file="$2"
  port="$3"
  health_url="$4"
  health_marker="$5"

  stopped="false"
  recorded_pid="$(read_file_trimmed "$pid_file" || true)"

  if pid_is_running "$recorded_pid"; then
    log "Stopping $name via pid file (pid=$recorded_pid)."
    stop_pid "$recorded_pid"
    stopped="true"
  fi

  listener_pid="$(find_listener_pid "$port")"
  if [ -n "$listener_pid" ]; then
    if url_contains "$health_url" "$health_marker"; then
      log "Stopping $name listener on port $port (pid=$listener_pid)."
      stop_pid "$listener_pid"
      stopped="true"
    elif [ "$stopped" != "true" ]; then
      fail "Port $port is occupied by a process that does not look like $name."
    fi
  fi

  rm -f "$pid_file"

  if [ "$stopped" = "true" ]; then
    log "$name stopped."
  else
    log "$name is not running."
  fi
}

main() {
  require_cmd curl
  require_cmd lsof

  stop_managed_service "frontend" "$FRONTEND_PID_FILE" 3000 "$FRONTEND_URL" "$FRONTEND_HTML_TITLE"
  stop_managed_service "backend" "$BACKEND_PID_FILE" 8065 "$BACKEND_URL" "$BACKEND_HTML_TITLE"
}

main "$@"
