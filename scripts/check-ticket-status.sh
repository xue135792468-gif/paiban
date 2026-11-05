#!/usr/bin/env bash
# Stale ticket status guard.
#
# A ticket marked `ready-for-agent` whose checklist is already complete is a
# historical snapshot: the next agent would redo finished work. This check makes
# that unambiguous case visible at merge/CI time. Unchecked boxes under a stale
# status stay a judgement call and are left to the reader.
#
# Usage: scripts/check-ticket-status.sh [root]
# Exit:  0 = no stale ticket status
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

  # 规则一（原有）：ready-for-agent 却已经把复选框勾完 —— 这是历史快照，
  # 下一个 agent 会照着票面把已经做完的活重做一遍。未勾完的待办票不拦。
  if [[ "$status" == ready-for-agent* ]]; then
    checked=$(grep -c '^- \[x\]' "$file" || true)
    unchecked=$(grep -c '^- \[ \]' "$file" || true)
    if [[ "$checked" -gt 0 && "$unchecked" -eq 0 ]]; then
      printf 'stale ticket status: %s (ready-for-agent, but all %s checklist items are checked)\n' \
        "${file#"$ROOT_DIR/"}" "$checked" >&2
      failed=1
    fi
    continue
  fi

  # 规则二（新增）：Status 已是 done 却还有未勾的复选框 —— 状态与清单不一致
  # （有人把已完成的票退回未勾，或改 done 时漏了清单），需要人来看一眼。
  # 只对 hold/ 生效：issues/ 下是各期历史归档票（一期/二期/四期等），票面复选框
  # 本来就是快照、从未回填；全量收紧会一次拦下 50 余张历史票，等于逼人改写历史。
  if [[ "$status" == done* && "$file" == */hold/* ]]; then
    unchecked=$(grep -c '^- \[ \]' "$file" || true)
    if [[ "$unchecked" -gt 0 ]]; then
      printf 'stale ticket status: %s (done, but %s checklist items are unchecked)\n' \
        "${file#"$ROOT_DIR/"}" "$unchecked" >&2
      failed=1
    fi
  fi
done < <(find "$ISSUES_DIR" \( -path '*/issues/*.md' -o -path '*/hold/*.md' \) -print0)

exit "$failed"
