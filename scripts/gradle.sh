#!/usr/bin/env bash
# Serialized Gradle wrapper for this workspace (multiple agents may build).
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJECT_ROOT/.tooling/dev-env.sh"
GRADLE_BIN="$PROJECT_ROOT/.tooling/gradle-dist/gradle-8.13/bin/gradle"
if [[ ! -x "$GRADLE_BIN" ]]; then
  GRADLE_BIN="$PROJECT_ROOT/gradlew"
fi
mkdir -p "$PROJECT_ROOT/.tooling"
exec flock "$PROJECT_ROOT/.tooling/gradle.lock" "$GRADLE_BIN" "$@"
