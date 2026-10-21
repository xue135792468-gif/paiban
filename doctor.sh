#!/usr/bin/env bash
# 自检命令入口（Linux/macOS/Git Bash）：逻辑在 scripts/doctor.sh。
# 退出码：0=健康，1=警告，2=严重。
set -uo pipefail
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/doctor.sh" "$@"
