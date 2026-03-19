#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
TOOLS_DIR="$PROJECT_ROOT/.tools"
CACHE_DIR="$TOOLS_DIR/cache"
JDKS_DIR="$TOOLS_DIR/jdks"
JDK_LINK="$TOOLS_DIR/jdk-17"

mkdir -p "$CACHE_DIR" "$JDKS_DIR"

if [ -x "$JDK_LINK/bin/java" ]; then
  echo "Project-local JDK 17 is ready: $JDK_LINK"
  exit 0
fi

OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
  Darwin) PLATFORM="mac" ;;
  Linux) PLATFORM="linux" ;;
  *)
    echo "Unsupported operating system: $OS" >&2
    exit 1
    ;;
esac

case "$ARCH" in
  arm64|aarch64) ARCH_LABEL="aarch64" ;;
  x86_64|amd64) ARCH_LABEL="x64" ;;
  *)
    echo "Unsupported CPU architecture: $ARCH" >&2
    exit 1
    ;;
esac

ARCHIVE_NAME="temurin-17-${PLATFORM}-${ARCH_LABEL}.tar.gz"
ARCHIVE_PATH="$CACHE_DIR/$ARCHIVE_NAME"
DOWNLOAD_URL="https://api.adoptium.net/v3/binary/latest/17/ga/${PLATFORM}/${ARCH_LABEL}/jdk/hotspot/normal/eclipse?project=jdk"

if [ ! -f "$ARCHIVE_PATH" ]; then
  echo "Downloading JDK 17 from Adoptium..."
  curl -fL "$DOWNLOAD_URL" -o "$ARCHIVE_PATH"
fi

echo "Extracting JDK 17 into $JDKS_DIR ..."
tar -xzf "$ARCHIVE_PATH" -C "$JDKS_DIR"

JDK_HOME=""
for candidate in "$JDKS_DIR"/jdk-17*; do
  if [ -x "$candidate/Contents/Home/bin/java" ]; then
    JDK_HOME="$candidate/Contents/Home"
    break
  fi
  if [ -x "$candidate/bin/java" ]; then
    JDK_HOME="$candidate"
    break
  fi
done

if [ -z "$JDK_HOME" ]; then
  echo "Failed to locate extracted JDK home under $JDKS_DIR" >&2
  exit 1
fi

ln -sfn "$JDK_HOME" "$JDK_LINK"
echo "Project-local JDK 17 is ready: $JDK_LINK"
