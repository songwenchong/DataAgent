#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
JDK_HOME_LINK="$PROJECT_ROOT/.tools/jdk-17"

if [ ! -x "$JDK_HOME_LINK/bin/java" ]; then
  "$SCRIPT_DIR/setup-local-jdk17.sh"
fi

export PROJECT_ROOT
export JAVA_HOME="$JDK_HOME_LINK"
export PATH="$JAVA_HOME/bin:$PATH"

echo "PROJECT_ROOT=$PROJECT_ROOT"
echo "JAVA_HOME=$JAVA_HOME"
