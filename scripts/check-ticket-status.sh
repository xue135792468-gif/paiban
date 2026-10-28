#!/usr/bin/env bash
# Stale ticket status guard.
#
# A ticket marked `ready-for-agent` whose checklist is already complete is a
# historical snapshot: the next agent would redo finished work. This check makes
# that unambiguous case visible at merge/CI time. Unchecked boxes under a stale
# status stay a judgement call and are left to the reader.
#
# Usage: scripts/check-ticket-status.sh [root]
# Exit:  0 = no all-checked ready-for-agent ticket
#        1 = at least one stale status
set -euo pipefail

ROOT_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ISSUES_DIR="$ROOT_DIR/.scratch"

if [[ ! -d "$ISSUES_DIR" ]]; then
  exit 0
fi

failed=0
while IFS= read -r -d '' file; do
  status=$(grep -m1 -E '^\*\*Status:\*\*|^Status:' "$file" \
    | sed -E 's/^\*\*Status:\*\*[[:space:]]*//; s/^Status:[[:space:]]*//' || true)
  [[ "$status" == ready-for-agent* ]] || continue
  checked=$(grep -c '^- \[x\]' "$file" || true)
  unchecked=$(grep -c '^- \[ \]' "$file" || true)
  if [[ "$checked" -gt 0 && "$unchecked" -eq 0 ]]; then
    printf 'stale ticket status: %s (ready-for-agent, but all %s checklist items are checked)\n' \
      "${file#"$ROOT_DIR/"}" "$checked" >&2
    failed=1
  fi
done < <(find "$ISSUES_DIR" -path '*/issues/*.md' -print0)

exit "$failed"
