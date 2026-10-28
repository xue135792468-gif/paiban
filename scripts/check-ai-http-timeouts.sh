#!/usr/bin/env bash
# AI strategy HTTP guard: every protocol implementation must send through AiHttpCalls,
# whose deadline covers the whole exchange (including a stalled response body).
# A direct HttpClient.send only honours the request timeout and can hang forever.
#
# Usage: scripts/check-ai-http-timeouts.sh [strategy-dir]
# Exit:  0 = no direct send; 1 = a strategy bypasses AiHttpCalls
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STRATEGY_DIR="${1:-$ROOT_DIR/backend/src/main/java/com/paiban/ai/strategy}"

if [[ ! -d "$STRATEGY_DIR" ]]; then
  echo "strategy dir not found: $STRATEGY_DIR" >&2
  exit 1
fi

failures=0
while IFS= read -r file; do
  echo "direct HttpClient.send in $file; use AiHttpCalls.send" >&2
  failures=$((failures + 1))
done < <(grep -RIl 'httpClient\.send(' "$STRATEGY_DIR" 2>/dev/null || true)

if [[ $failures -gt 0 ]]; then
  echo "AI HTTP timeout check failed: $failures file(s) bypass AiHttpCalls" >&2
  exit 1
fi

echo "AI HTTP timeouts ok (strategies go through AiHttpCalls)"
