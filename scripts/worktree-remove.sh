#!/usr/bin/env bash
# Removes a ticket worktree without following the node_modules junction.
# Usage: scripts/worktree-remove.sh <name>
set -euo pipefail

name=${1:?usage: worktree-remove.sh <name>}

root=$(cd "$(dirname "$0")/.." && pwd)
wt_root=${PAIBAN_WT_ROOT:-"$(dirname "$root")/paiban-wt"}
wt="$wt_root/$name"

if [ -e "$wt/frontend/node_modules" ]; then
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      link=$(cygpath -w "$wt/frontend/node_modules" 2>/dev/null || printf '%s' "$wt/frontend/node_modules")
      # DirectoryInfo.Delete() removes the reparse point only; rm -rf would
      # recurse into the shared node_modules and delete the main checkout's deps.
      powershell -NoProfile -Command "(Get-Item '$link').Delete()"
      ;;
    *)
      rm -f "$wt/frontend/node_modules"
      ;;
  esac
fi

if [ -e "$wt/frontend/node_modules" ]; then
  echo "refusing to remove: junction still present at $wt/frontend/node_modules" >&2
  exit 1
fi

git -C "$root" worktree remove "$wt" --force 2>/dev/null || true
rm -rf "$wt"
echo "removed: $wt"
