#!/usr/bin/env bash
# Read-only substitution CLI: plan / verify. No database, no business writes.
# Build the backend jar first: (cd backend && ./mvnw -DskipTests package)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.tools/env.sh" ]; then
  # shellcheck disable=SC1091
  source "$ROOT/.tools/env.sh"
fi

JAR="$ROOT/backend/target/paiban-backend-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "未找到后端 jar。先构建：cd backend && ./mvnw -DskipTests package" >&2
  exit 1
fi

exec java -cp "$JAR" \
  -Dloader.main=com.paiban.substitution.cli.SubstitutionCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
