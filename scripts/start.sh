#!/usr/bin/env bash
# 排课系统一键启动（Linux / macOS）。
# 入口是仓库根目录的 start.sh；本文件承载实际逻辑。
#
# 退出码：0 成功；1 启动失败；2 环境或前置条件不满足。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/env.sh
. "$SCRIPT_DIR/lib/env.sh"

QUIET=0
NO_BUILD=0
ENV_ONLY=0
ADMIN_PASSWORD=""
TIMEOUT=60

info() { [ "$QUIET" = "1" ] || printf '%s\n' "$*"; }
warn() { printf '[警告] %s\n' "$*" >&2; }
err() { printf '[错误] %s\n' "$*" >&2; }

usage() {
  cat <<'EOF'
排课系统一键启动

用法: ./start.sh [选项]

  --quiet, --no-prompt       非交互模式：不打印进度、不等待输入
  --admin-password=VALUE     指定初始管理员密码（默认 admin123）
  --timeout=SECONDS          等待后端就绪的最长秒数（默认 60）
  --no-build                 不重新构建镜像，直接启动已有镜像
  --env-only                 只生成/检查 .env，不启动服务
  -h, --help                 显示本帮助

退出码: 0 成功, 1 启动失败, 2 环境或前置条件不满足
EOF
}

show_failure_hint() {
  printf '\n排查建议：\n' >&2
  printf '  1. 查看后端日志：docker compose logs backend\n' >&2
  printf '  2. 运行自检：doctor.bat（Windows）或 ./doctor.sh（Linux/macOS）\n' >&2
  printf '  3. 首次构建较慢时可加大等待时间：--timeout=180\n' >&2
}

mem_available_bytes() {
  if [ -r /proc/meminfo ]; then
    awk '/^MemAvailable:/ { printf "%d\n", $2 * 1024; exit }' /proc/meminfo
  elif command -v sysctl >/dev/null 2>&1; then
    # macOS 没有 MemAvailable，用总内存作为近似值。
    sysctl -n hw.memsize 2>/dev/null || true
  fi
}

port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk -v p=":$port" 'NR > 1 && $4 ~ p "$" { found = 1 } END { exit(found ? 0 : 1) }'
  elif command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -Eq "[:.]${port}[[:space:]]+.*LISTEN"
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
  fi
}

port_owner() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -Fc 2>/dev/null | sed -n 's/^c//p' | head -n 1
  elif command -v ss >/dev/null 2>&1; then
    ss -ltnp 2>/dev/null | awk -v p=":$port" '$4 ~ p "$" { print; exit }' |
      sed -n 's/.*users:(("\([^"]*\)".*/\1/p'
  fi
}

health_ok() {
  local body
  if command -v curl >/dev/null 2>&1; then
    body="$(curl -fsS --max-time 2 http://127.0.0.1:8080/api/health 2>/dev/null)" || return 1
    case "$body" in
      *'"UP"'*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  if command -v wget >/dev/null 2>&1; then
    body="$(wget -q -O - --timeout=2 http://127.0.0.1:8080/api/health 2>/dev/null)" || return 1
    case "$body" in
      *'"UP"'*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  return 1
}

wait_health() {
  local timeout="$1"
  local deadline
  deadline=$(( $(date +%s) + timeout ))
  while :; do
    if health_ok; then
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      return 1
    fi
    sleep 2
  done
}

# --------------------------------------------------------------- 参数解析
while [ $# -gt 0 ]; do
  case "$1" in
    --quiet | --no-prompt)
      QUIET=1
      ;;
    --no-build)
      NO_BUILD=1
      ;;
    --env-only)
      ENV_ONLY=1
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    --admin-password=*)
      ADMIN_PASSWORD="${1#*=}"
      if [ -z "$ADMIN_PASSWORD" ]; then
        err '--admin-password 不能为空'
        exit 2
      fi
      ;;
    --timeout=*)
      TIMEOUT="${1#*=}"
      case "$TIMEOUT" in
        '' | *[!0-9]*)
          err '--timeout 必须是正整数'
          exit 2
          ;;
      esac
      if [ "$TIMEOUT" -le 0 ]; then
        err '--timeout 必须大于 0'
        exit 2
      fi
      ;;
    *)
      err "未知参数：$1（运行 ./start.sh --help 查看用法）"
      exit 2
      ;;
  esac
  shift
done

# ------------------------------------------------------- 仅准备 .env 模式
if [ "$ENV_ONLY" = "1" ]; then
  if paiban_ensure_env "$ROOT" "$ADMIN_PASSWORD" "$QUIET"; then
    info "[完成] .env 已就绪：$PAIBAN_ENV_FILE"
    exit 0
  fi
  exit 2
fi

# ------------------------------------------------------------ 1. Docker
info '[1/5] 检查 Docker ...'
if ! command -v docker >/dev/null 2>&1; then
  err '未检测到 Docker。'
  printf '请先安装 Docker Desktop：https://www.docker.com/products/docker-desktop\n' >&2
  printf '安装完成后重新运行本脚本。\n' >&2
  exit 2
fi

if ! docker info >/dev/null 2>&1; then
  err 'Docker 未启动，请打开 Docker Desktop，等待状态显示为运行中后重试。'
  exit 2
fi

COMPOSE=()
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  err '未找到 docker compose。请安装 Docker Desktop（自带 Compose）后重试。'
  exit 2
fi

# ---------------------------------------------------- 2. 内存与端口
info '[2/5] 检查内存与端口 ...'
MEM_BYTES="$(mem_available_bytes || true)"
case "$MEM_BYTES" in
  '' | *[!0-9]*) MEM_BYTES='' ;;
esac
if [ -n "$MEM_BYTES" ] && [ "$MEM_BYTES" -lt 2147483648 ]; then
  warn "可用内存约 $((MEM_BYTES / 1024 / 1024)) MB，低于建议的 2 GB，构建和排课可能较慢。"
fi

for spec in '8080:后端服务' '5173:前端页面' '5432:PostgreSQL 数据库'; do
  PORT="${spec%%:*}"
  NAME="${spec#*:}"
  if port_in_use "$PORT"; then
    OWNER="$(port_owner "$PORT" || true)"
    if [ -n "$OWNER" ]; then
      warn "端口 $PORT（$NAME）已被占用：$OWNER。如果本系统已经在运行，可以忽略；否则请先释放该端口。"
    else
      warn "端口 $PORT（$NAME）已被占用。如果本系统已经在运行，可以忽略；否则请先释放该端口。"
    fi
  fi
done

# ------------------------------------------------------------ 3. .env
info '[3/5] 准备 .env 密钥 ...'
if ! paiban_ensure_env "$ROOT" "$ADMIN_PASSWORD" "$QUIET"; then
  exit 2
fi
ADMIN_PASSWORD="$PAIBAN_EFFECTIVE_ADMIN_PASSWORD"

# ------------------------------------------------------------ 4. compose
info '[4/5] 构建并启动容器（首次可能需要几分钟）...'
COMPOSE_ARGS=(up -d)
if [ "$NO_BUILD" != "1" ]; then
  COMPOSE_ARGS+=(--build)
fi

COMPOSE_LOG="$ROOT/.tools/logs/start-compose.log"
if [ "$QUIET" = "1" ]; then
  mkdir -p "$ROOT/.tools/logs"
  if ! (cd "$ROOT" && "${COMPOSE[@]}" "${COMPOSE_ARGS[@]}") >"$COMPOSE_LOG" 2>&1; then
    err '容器启动失败（构建或启动阶段出错）。'
    printf '最近日志：\n' >&2
    tail -n 40 "$COMPOSE_LOG" >&2 || true
    show_failure_hint
    exit 1
  fi
else
  if ! (cd "$ROOT" && "${COMPOSE[@]}" "${COMPOSE_ARGS[@]}"); then
    err '容器启动失败（构建或启动阶段出错）。'
    show_failure_hint
    exit 1
  fi
fi

# ------------------------------------------------------------ 5. 健康检查
info "[5/5] 等待后端就绪（最多 ${TIMEOUT} 秒）..."
if ! wait_health "$TIMEOUT"; then
  err "后端在 ${TIMEOUT} 秒内没有就绪。"
  printf '可能原因：首次构建尚未完成、内存不足、或后端启动报错。\n' >&2
  show_failure_hint
  exit 1
fi

# ------------------------------------------------------------ 完成
printf '\n[完成] 排课系统已启动\n'
printf '  前端地址: http://localhost:5173\n'
printf '  后端地址: http://localhost:8080\n'
printf '  默认账号: admin / %s\n' "$ADMIN_PASSWORD"
printf '\n首次使用请访问前端地址，按初始化向导操作。\n'
printf '停止服务: docker compose down\n'
exit 0
