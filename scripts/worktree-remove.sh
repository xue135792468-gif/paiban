#!/usr/bin/env bash
# Removes a ticket worktree without following its junctions.
# Usage: scripts/worktree-remove.sh <name>
set -euo pipefail

name=${1:?usage: worktree-remove.sh <name>}

root=$(cd "$(dirname "$0")/.." && pwd)
wt_root=${PAIBAN_WT_ROOT:-"$(dirname "$root")/paiban-wt"}
wt="$wt_root/$name"

remove_link() {
  local link=$1
  [ -e "$link" ] || return 0
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      local wlink
      wlink=$(cygpath -w "$link" 2>/dev/null || printf '%s' "$link")
      # DirectoryInfo.Delete() removes the reparse point only; rm -rf would
      # recurse into the shared target and delete the main checkout's files.
      powershell -NoProfile -Command "(Get-Item '$wlink').Delete()"
      ;;
    *)
      rm -f "$link"
      ;;
  esac
  if [ -e "$link" ]; then
    echo "refusing to remove: junction still present at $link" >&2
    exit 1
  fi
}

remove_link "$wt/frontend/node_modules"
remove_link "$wt/.tools"

git -C "$root" worktree remove "$wt" --force 2>/dev/null || true
rm -rf "$wt"
echo "removed: $wt"
