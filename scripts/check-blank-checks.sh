#!/usr/bin/env bash
# isBlank ratchet for CODING_STANDARDS.md.
#
# New backend code must use Spring's StringUtils.hasText: any `isBlank(` in
# backend/src/main/java is a finding. The 171-ish existing hits are listed in
# scripts/blank-check-baseline.txt with their current count: they may shrink but
# must never grow. Delete a line once its file reaches 0.
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT=backend/src/main/java
BASELINE=scripts/blank-check-baseline.txt
PATTERN='isBlank('
declare -A baseline=()

if [ -f "$BASELINE" ]; then
  while IFS=$'\t' read -r path count; do
    path=${path%$'\r'}
    count=${count%$'\r'}
    [ -z "${path:-}" ] && continue
    case "$path" in \#*) continue ;; esac
    baseline["$path"]=$count
  done < "$BASELINE"
fi

failed=0
while IFS= read -r file; do
  hits=$(grep -oF "$PATTERN" "$file" | wc -l || true)
  hits=$((hits))
  [ "$hits" -eq 0 ] && continue
  allowed=${baseline[$file]:-}
  if [ -z "$allowed" ]; then
    printf 'isBlank in a file that is not on the baseline: %s (%s hit(s))\n' "$file" "$hits"
    grep -nF "$PATTERN" "$file" | sed 's/^/  /'
    failed=1
  elif [ "$hits" -gt "$allowed" ]; then
    printf 'isBlank grew: %s (%s > baseline %s)\n' "$file" "$hits" "$allowed"
    grep -nF "$PATTERN" "$file" | sed 's/^/  /'
    failed=1
  elif [ "$hits" -lt "$allowed" ]; then
    printf 'baseline can be lowered: %s (%s < %s)\n' "$file" "$hits" "$allowed"
  fi
done < <(find "$ROOT" -type f -name '*.java' | sort)

if [ "$failed" -ne 0 ]; then
  echo "Use StringUtils.hasText for new blank checks; baseline files may only shrink."
  exit 1
fi

echo "isBlank ok (no new hits; baseline files did not grow)"
