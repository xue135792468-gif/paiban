#!/usr/bin/env bash
# 排课系统一键启动入口（Linux / macOS）。
# 实际逻辑在 scripts/start.sh；这里只负责定位仓库根目录并转发参数。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${BASH:-bash}" "$ROOT/scripts/start.sh" "$@"
