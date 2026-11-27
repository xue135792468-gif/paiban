#!/usr/bin/env bash
# Thin front end for the verification receipt tool (check subcommand).
#
#   bash scripts/check-verification-receipts.sh [--staged|--worktree] [run-id]
#
# Default: check the receipts committed in HEAD. --staged: check the Git index
# blobs. --worktree: check workspace bytes (explicitly labelled, not Git object
# validation). Reads only: the Git index is never written.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

if [[ -f "$ROOT/.tools/env.sh" ]]; then
  # shellcheck disable=SC1091
  . "$ROOT/.tools/env.sh"
fi

PYTHON_CMD=()

find_python() {
  local candidate resolved
  for candidate in "${PAIBAN_PYTHON:-}" python3 python; do
    [[ -n "$candidate" ]] || continue
    resolved="$(command -v "$candidate" 2>/dev/null)" || continue
    [[ -n "$resolved" ]] || continue
    case "$resolved" in
      *WindowsApps*) continue ;;
    esac
    if "$resolved" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; then
      PYTHON_CMD=("$resolved")
      return 0
    fi
  done
  if command -v py >/dev/null 2>&1 \
    && py -3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; then
    PYTHON_CMD=(py -3)
    return 0
  fi
  return 1
}

if ! find_python; then
  printf 'ERROR=no Python 3.9+ interpreter found; set PAIBAN_PYTHON\n' >&2
  exit 65
fi

exec "${PYTHON_CMD[@]}" "$HERE/verification_receipts.py" check "$@"
