#!/usr/bin/env bash
# Flyway migration version guard.
#
# Parallel branches may each reserve new versions. A merge that brings two files
# with the same numeric version (for example V30__a.sql and V30__b.sql) fails only
# when the application starts and Flyway validates the merged directory. This
# check catches that collision at merge/CI time with a readable message.
#
# Gaps are allowed: a parallel branch may reserve V30 while this branch uses
# V31-V37, so only duplicates and malformed names fail.
#
# Usage: scripts/check-migrations.sh [migration-dir]
# Exit:  0 = every migration has a unique version and a valid name
#        1 = duplicate version or malformed filename
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_DIR="${1:-$ROOT_DIR/backend/src/main/resources/db/migration}"

if [[ ! -d "$MIGRATION_DIR" ]]; then
  echo "migration dir not found: $MIGRATION_DIR" >&2
  exit 1
fi

declare -A seen=()
failures=0
shopt -s nullglob

for file in "$MIGRATION_DIR"/V*.sql; do
  name="$(basename "$file")"
  if [[ ! "$name" =~ ^V([0-9]+)__[A-Za-z0-9_]+\.sql$ ]]; then
    echo "invalid migration filename: $name (expected V<number>__<snake_case>.sql)" >&2
    failures=$((failures + 1))
    continue
  fi

  # Normalise leading zeros so V030 and V30 collide like Flyway would.
  version=$((10#${BASH_REMATCH[1]}))
  if [[ -n "${seen[$version]:-}" ]]; then
    echo "duplicate migration version V$version: ${seen[$version]} and $name" >&2
    failures=$((failures + 1))
  else
    seen[$version]="$name"
  fi
done

if [[ $failures -gt 0 ]]; then
  echo "migration check failed: $failures problem(s)" >&2
  exit 1
fi

echo "migrations ok: ${#seen[@]} unique versions"
