#!/usr/bin/env bash
# Creates an isolated worktree for a ticket and links the shared node_modules.
# Usage: scripts/worktree-add.sh [--allow-stale-base] <name> <branch> [base]
#   --allow-stale-base  allow branching from a revision behind origin/main
#   name                directory under $PAIBAN_WT_ROOT (default: sibling paiban-wt/)
#   branch              new branch to create
#   base                base revision (default: origin/main if present, else main)
set -euo pipefail

allow_stale=0
if [ "${1:-}" = "--allow-stale-base" ]; then
  allow_stale=1
  shift
fi

name=${1:?usage: worktree-add.sh [--allow-stale-base] <name> <branch> [base]}
branch=${2:?usage: worktree-add.sh [--allow-stale-base] <name> <branch> [base]}
base=${3:-}

root=$(cd "$(dirname "$0")/.." && pwd)
git -C "$root" fetch --quiet origin main || true

has_origin_main=0
if git -C "$root" rev-parse --verify --quiet origin/main >/dev/null; then
  has_origin_main=1
fi

if [ -z "$base" ]; then
  if [ "$has_origin_main" -eq 1 ]; then
    base="origin/main"
  else
    base="main"
  fi
fi

if [ "$allow_stale" -eq 0 ] && [ "$has_origin_main" -eq 1 ]; then
  if ! git -C "$root" merge-base --is-ancestor origin/main "$base"; then
    behind=$(git -C "$root" rev-list --count "$base..origin/main")
    echo "worktree-add: base '$base' is $behind commit(s) behind origin/main. Use origin/main (default) or pass --allow-stale-base if intentional." >&2
    exit 1
  fi
fi

wt_root=${PAIBAN_WT_ROOT:-"$(dirname "$root")/paiban-wt"}
wt="$wt_root/$name"

if [ -e "$wt" ]; then
  echo "worktree already exists: $wt" >&2
  exit 1
fi

mkdir -p "$wt_root"
git -C "$root" worktree add "$wt" -b "$branch" "$base"

link_dir() {
  local target=$1 link=$2
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      local wtarget wlink
      wtarget=$(cygpath -w "$target" 2>/dev/null || printf '%s' "$target")
      wlink=$(cygpath -w "$link" 2>/dev/null || printf '%s' "$link")
      powershell -NoProfile -Command "New-Item -ItemType Junction -Path '$wlink' -Target '$wtarget' | Out-Null"
      ;;
    *)
      ln -s "$target" "$link"
      ;;
  esac
}

link_dir "$root/frontend/node_modules" "$wt/frontend/node_modules"
# Playwright's webServer spawns mvnw.cmd, which needs a Windows JAVA_HOME from
# .tools; without this link `verify.sh --full` cannot start the backend.
if [ -d "$root/.tools" ]; then
  link_dir "$root/.tools" "$wt/.tools"
fi

echo "worktree ready: $wt"
echo "  cd $wt"
echo "  source .tools/env.sh"
