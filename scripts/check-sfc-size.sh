#!/usr/bin/env bash
# SFC size ratchet for CODING_STANDARDS.md.
#
# New .vue files must stay <= 600 lines. Files already over the limit are listed
# in scripts/sfc-size-baseline.txt with their current size: they may shrink but
# must never grow. When one drops to <= 600, delete its baseline line.
set -euo pipefail
cd "$(dirname "$0")/.."

MAX=${MAX_SFC_LINES:-600}
BASELINE=scripts/sfc-size-baseline.txt
declare -A baseline=()

if [ -f "$BASELINE" ]; then
  while read -r path max; do
    # A Windows checkout hands this file CRLF endings; a trailing \r would stop
    # the path from matching and turn the numeric test into a syntax error.
    path=${path%$'\r'}
    max=${max%$'\r'}
    [ -z "${path:-}" ] && continue
    case "$path" in \#*) continue ;; esac
    baseline["$path"]=$max
  done < "$BASELINE"
fi

failed=0
while IFS= read -r file; do
  lines=$(wc -l < "$file")
  if [ -n "${baseline[$file]:-}" ]; then
    if [ "$lines" -gt "${baseline[$file]}" ]; then
      printf 'SFC grew: %s (%s > baseline %s)\n' "$file" "$lines" "${baseline[$file]}"
      failed=1
    elif [ "$lines" -le "$MAX" ]; then
      printf 'baseline can be removed: %s (%s <= %s)\n' "$file" "$lines" "$MAX"
    fi
  elif [ "$lines" -gt "$MAX" ]; then
    printf 'SFC too long: %s (%s > %s)\n' "$file" "$lines" "$MAX"
    failed=1
  fi
done < <(find frontend/src -type f -name '*.vue' | sort)

if [ "$failed" -ne 0 ]; then
  echo "Split new files to <= $MAX lines; existing oversized files may only shrink."
  exit 1
fi

echo "SFC size ok (new <= $MAX lines; baseline files did not grow)"
