#!/usr/bin/env bash
# Creates an isolated worktree for a ticket and links the shared node_modules.
# Usage: scripts/worktree-add.sh <name> <branch> [base]
#   name   directory under $PAIBAN_WT_ROOT (default: sibling paiban-wt/)
#   branch new branch to create
#   base   base revision (default: main)
set -euo pipefail

name=${1:?usage: worktree-add.sh <name> <branch> [base]}
branch=${2:?usage: worktree-add.sh <name> <branch> [base]}
base=${3:-main}

root=$(cd "$(dirname "$0")/.." && pwd)
wt_root=${PAIBAN_WT_ROOT:-"$(dirname "$root")/paiban-wt"}
wt="$wt_root/$name"

if [ -e "$wt" ]; then
  echo "worktree already exists: $wt" >&2
  exit 1
fi

mkdir -p "$wt_root"
git -C "$root" worktree add "$wt" -b "$branch" "$base"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    target=$(cygpath -w "$root/frontend/node_modules" 2>/dev/null || printf '%s' "$root/frontend/node_modules")
    link=$(cygpath -w "$wt/frontend/node_modules" 2>/dev/null || printf '%s' "$wt/frontend/node_modules")
    powershell -NoProfile -Command "New-Item -ItemType Junction -Path '$link' -Target '$target' | Out-Null"
    ;;
  *)
    ln -s "$root/frontend/node_modules" "$wt/frontend/node_modules"
    ;;
esac

echo "worktree ready: $wt"
echo "  cd $wt"
echo "  source $root/.tools/env.sh   # worktree 内没有 .tools"
