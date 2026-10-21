#!/usr/bin/env bash
# 排课系统自检（doctor）：Docker、端口、内存、数据库、磁盘。
#
# 用法：
#   ./doctor.sh            人类可读的中文报告
#   ./doctor.sh --json     机器可读的 JSON（stdout 只有 JSON）
#   ./doctor.sh --quiet    同 --json（本工具无进度输出）
#   ./doctor.sh --help     显示帮助
#
# 退出码：0=健康，1=警告（内存/迁移），2=严重（Docker/数据库/磁盘）。
# 端口占用只作为信息输出，不影响退出码：本系统正常运行时它们本来就被自己占用。
set -uo pipefail

export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'EOF'
排课系统自检（doctor）

用法：doctor.sh [--json|--quiet] [--help]

  --json    输出 JSON 诊断报告，stdout 只有 JSON
  --quiet   同 --json（本工具不打印进度信息）
  --help    显示本帮助

退出码：0=健康，1=有警告（内存/迁移），2=存在严重问题
端口占用仅为信息项，不影响退出码。
EOF
}

MODE="human"
for arg in "$@"; do
  case "$arg" in
    --json | --quiet) MODE="json" ;;
    --help | -h)
      usage
      exit 0
      ;;
    *)
      printf '未知参数：%s\n\n' "$arg" >&2
      usage >&2
      exit 2
      ;;
  esac
done

# --- 小工具 ---------------------------------------------------------------

# 优先使用 timeout/gtimeout，缺失时直接执行（避免在 macOS 上因缺少命令失败）。
run_timeout() {
  local secs="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$secs" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$secs" "$@"
  else
    "$@"
  fi
}

bool_str() {
  if [[ "${1:-0}" -eq 1 ]]; then printf 'true'; else printf 'false'; fi
}

json_str_or_null() {
  local value="${1:-}"
  if [[ -z "$value" ]]; then
    printf 'null'
  else
    printf '"%s"' "$(printf '%s' "$value" | tr -d '\r\n' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
  fi
}

# 千字节 -> GB，保留一位小数。
kb_to_gb() {
  awk -v kb="${1:-0}" 'BEGIN { printf "%.1f", kb / 1048576 }'
}

# 读取 .env 中的键（不存在时输出空串）。兼容 CRLF、export 前缀和引号。
env_value() {
  local key="$1"
  local file="$ROOT/.env"
  local line
  [[ -f "$file" ]] || return 0
  line="$(tr -d '\r' <"$file" | grep -E "^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=" | tail -1)"
  [[ -n "$line" ]] || return 0
  printf '%s' "${line#*=}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
    -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'\$//"
}

# 端口被占用（能连上）返回 occupied，否则 free。不解析 netstat，跨平台。
port_state() {
  local port="$1"
  if run_timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/${port}" 2>/dev/null; then
    printf 'occupied'
  else
    printf 'free'
  fi
}

# --- Docker ---------------------------------------------------------------

docker_installed=0
docker_running=0
docker_version=""

if command -v docker >/dev/null 2>&1; then
  docker_installed=1
  raw_version="$(run_timeout 10 docker --version 2>/dev/null | head -1 | tr -d '\r')"
  docker_version="$(printf '%s' "$raw_version" | sed -n 's/.*[Vv]ersion[[:space:]]\+\([^,]*\).*/\1/p')"
  [[ -n "$docker_version" ]] || docker_version="$raw_version"
  if run_timeout 20 docker info >/dev/null 2>&1; then
    docker_running=1
  fi
fi

# --- 端口 -----------------------------------------------------------------

port_8080="$(port_state 8080)"
port_5173="$(port_state 5173)"
port_5432="$(port_state 5432)"

# --- 内存 -----------------------------------------------------------------

os_name="$(uname -s 2>/dev/null || echo unknown)"
total_kb=0
avail_kb=0

case "$os_name" in
  MINGW* | MSYS* | CYGWIN*)
    if command -v powershell >/dev/null 2>&1; then
      mem_raw="$(run_timeout 25 powershell -NoProfile -NonInteractive -Command \
        '$os = Get-CimInstance Win32_OperatingSystem; "$($os.TotalVisibleMemorySize) $($os.FreePhysicalMemory)"' \
        2>/dev/null | tr -d '\r')"
      total_kb="$(printf '%s' "$mem_raw" | awk 'NF >= 2 { print $1 }')"
      avail_kb="$(printf '%s' "$mem_raw" | awk 'NF >= 2 { print $2 }')"
    fi
    ;;
  Darwin)
    total_bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
    total_kb=$((total_bytes / 1024))
    page_size="$(sysctl -n hw.pagesize 2>/dev/null || echo 4096)"
    vm="$(vm_stat 2>/dev/null || true)"
    free_pages="$(printf '%s\n' "$vm" | awk -F: '/Pages free/ { gsub(/[^0-9]/, "", $2); print $2 }')"
    inactive_pages="$(printf '%s\n' "$vm" | awk -F: '/Pages inactive/ { gsub(/[^0-9]/, "", $2); print $2 }')"
    avail_kb=$((((${free_pages:-0} + ${inactive_pages:-0}) * page_size) / 1024))
    ;;
  *)
    if [[ -r /proc/meminfo ]]; then
      total_kb="$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)"
      avail_kb="$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo)"
      [[ -n "$avail_kb" ]] || avail_kb="$(awk '/^MemFree:/ { print $2 }' /proc/meminfo)"
    fi
    ;;
esac

[[ "$total_kb" =~ ^[0-9]+$ ]] || total_kb=0
[[ "$avail_kb" =~ ^[0-9]+$ ]] || avail_kb=0

total_gb="$(kb_to_gb "$total_kb")"
avail_gb="$(kb_to_gb "$avail_kb")"
mem_sufficient=0
[[ "$avail_kb" -ge 2097152 ]] && mem_sufficient=1

# --- 数据库 ---------------------------------------------------------------

db_reachable=0
db_migrated=0
db_version=""

if [[ $docker_running -eq 1 && -f "$ROOT/docker-compose.yml" ]]; then
  db_user="$(env_value POSTGRES_USER)"
  db_name="$(env_value POSTGRES_DB)"
  [[ -n "$db_user" ]] || db_user="paiban"
  [[ -n "$db_name" ]] || db_name="paiban"

  compose=(docker compose -f "$ROOT/docker-compose.yml")

  if run_timeout 15 "${compose[@]}" exec -T postgres pg_isready -U "$db_user" -d "$db_name" \
    >/dev/null 2>&1; then
    db_reachable=1
    db_version="$(run_timeout 15 "${compose[@]}" exec -T postgres psql \
      -U "$db_user" -d "$db_name" -tAc \
      'select version from flyway_schema_history order by installed_rank desc limit 1' \
      2>/dev/null | tr -d '[:space:]')"
    if [[ -n "$db_version" ]]; then
      db_migrated=1
    fi
  fi
fi

# --- 磁盘 -----------------------------------------------------------------

free_kb="$(df -Pk "$ROOT" 2>/dev/null | awk 'NR == 2 { print $4 }')"
[[ "$free_kb" =~ ^[0-9]+$ ]] || free_kb=0

project_free_gb="$(kb_to_gb "$free_kb")"
disk_sufficient=0
[[ "$free_kb" -ge 1048576 ]] && disk_sufficient=1

# 只有在项目盘就是 E 盘时才填 e_drive_free_gb。
e_drive_free_gb="null"
case "$os_name" in
  MINGW* | MSYS* | CYGWIN*)
    win_root="$(cd "$ROOT" && pwd -W 2>/dev/null || true)"
    drive_letter="${win_root%%:*}"
    if [[ "${drive_letter^^}" == "E" ]]; then
      e_drive_free_gb="$project_free_gb"
    fi
    ;;
esac

# --- 退出码 ---------------------------------------------------------------
# 端口占用只是信息：本系统正常运行时 8080/5173/5432 本来就被自己占着。
# 2 = 严重（Docker/数据库/磁盘）；1 = 警告（内存/迁移）；0 = 健康。

exit_code=0
if [[ $docker_installed -eq 0 || $docker_running -eq 0 || $db_reachable -eq 0 || $disk_sufficient -eq 0 ]]; then
  exit_code=2
elif [[ $mem_sufficient -eq 0 || $db_migrated -eq 0 ]]; then
  exit_code=1
fi

# --- 输出 -----------------------------------------------------------------

if [[ "$MODE" == "json" ]]; then
  printf '{\n'
  printf '  "docker": {"installed": %s, "version": %s, "running": %s},\n' \
    "$(bool_str "$docker_installed")" "$(json_str_or_null "$docker_version")" "$(bool_str "$docker_running")"
  printf '  "ports": {"8080": "%s", "5173": "%s", "5432": "%s"},\n' \
    "$port_8080" "$port_5173" "$port_5432"
  printf '  "memory": {"total_gb": %s, "available_gb": %s, "sufficient": %s},\n' \
    "$total_gb" "$avail_gb" "$(bool_str "$mem_sufficient")"
  printf '  "database": {"reachable": %s, "migrated": %s, "flyway_version": %s},\n' \
    "$(bool_str "$db_reachable")" "$(bool_str "$db_migrated")" "$(json_str_or_null "$db_version")"
  printf '  "disk": {"e_drive_free_gb": %s, "project_free_gb": %s, "sufficient": %s},\n' \
    "$e_drive_free_gb" "$project_free_gb" "$(bool_str "$disk_sufficient")"
  printf '  "exit_code": %s\n' "$exit_code"
  printf '}\n'
  exit "$exit_code"
fi

# 只在真正输出到终端时使用颜色。
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'
  C_RED=$'\033[31m'
  C_RESET=$'\033[0m'
else
  C_GREEN=""
  C_YELLOW=""
  C_RED=""
  C_RESET=""
fi

ok() { printf '%s✅%s %s\n' "$C_GREEN" "$C_RESET" "$1"; }
warn() { printf '%s⚠️%s %s\n' "$C_YELLOW" "$C_RESET" "$1"; }
bad() { printf '%s❌%s %s\n' "$C_RED" "$C_RESET" "$1"; }
info() { printf 'ℹ️ %s\n' "$1"; }

echo "=== 排课系统自检 (doctor) ==="

if [[ $docker_installed -eq 0 ]]; then
  bad "Docker：未安装。请安装 Docker Desktop：https://www.docker.com/products/docker-desktop"
elif [[ $docker_running -eq 0 ]]; then
  warn "Docker：已安装 ${docker_version}，但守护进程未运行（请启动 Docker Desktop）"
else
  ok "Docker：已安装 ${docker_version}，守护进程运行中"
fi

if [[ "$port_8080" == "free" && "$port_5173" == "free" && "$port_5432" == "free" ]]; then
  ok "端口：8080 / 5173 / 5432 均空闲"
else
  detail=""
  [[ "$port_8080" == "occupied" ]] && detail+="8080（后端） "
  [[ "$port_5173" == "occupied" ]] && detail+="5173（前端） "
  [[ "$port_5432" == "occupied" ]] && detail+="5432（数据库） "
  info "端口：${detail}已被占用（若为本系统容器则正常；否则请先释放，不影响退出码）"
fi

if [[ $mem_sufficient -eq 1 ]]; then
  ok "内存：总计 ${total_gb} GB，可用 ${avail_gb} GB（要求 ≥ 2 GB）"
else
  warn "内存：总计 ${total_gb} GB，可用 ${avail_gb} GB，低于 2 GB，求解可能失败"
fi

if [[ $docker_running -eq 0 ]]; then
  bad "数据库：不可连接（Docker 未运行；启动后执行 docker compose up -d）"
elif [[ $db_reachable -eq 0 ]]; then
  bad "数据库：不可连接（请执行 docker compose up -d 并等待就绪）"
elif [[ $db_migrated -eq 0 ]]; then
  warn "数据库：可连接，但尚未执行 Flyway 迁移（后端首次启动会自动建表）"
else
  ok "数据库：可连接，Flyway 版本 ${db_version}"
fi

if [[ $disk_sufficient -eq 1 ]]; then
  ok "磁盘：项目盘剩余 ${project_free_gb} GB（要求 ≥ 1 GB）"
else
  bad "磁盘：项目盘剩余 ${project_free_gb} GB，低于 1 GB"
fi

case "$exit_code" in
  0) printf '\n结论：%s环境健康%s (exit_code=0)\n' "$C_GREEN" "$C_RESET" ;;
  1) printf '\n结论：%s可用但有警告%s (exit_code=1)\n' "$C_YELLOW" "$C_RESET" ;;
  *) printf '\n结论：%s存在严重问题，请先修复%s (exit_code=2)\n' "$C_RED" "$C_RESET" ;;
esac

exit "$exit_code"
