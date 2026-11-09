#!/usr/bin/env bash
# 排课系统 · 单用户版一键安装（Linux 服务器）
#
# 目标：在一台干净的 Linux 机器上，用一条命令把整套系统跑起来。
#       幂等：重复执行不会覆盖已有 .env / 证书 / 数据。
#
# 用法：
#   sudo ./scripts/install.sh                 # 自签证书，默认登录地址 https://<本机IP>
#   sudo ./scripts/install.sh --domain jw.example.com
#   sudo ./scripts/install.sh --no-build      # 镜像已存在时跳过构建
#
# 退出码：0 成功；1 前置条件/构建/健康检查失败（失败原因打印在最后一行）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DOMAIN=""
DO_BUILD=1
SKIP_PORT_CHECK=0
HEALTH_TIMEOUT=600

while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    --skip-port-check) SKIP_PORT_CHECK=1; shift ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1"; exit 1 ;;
  esac
done

say()  { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
die()  { printf '\n\033[31m安装失败：%s\033[0m\n' "$1" >&2; exit 1; }

rand_hex() { openssl rand -hex "${1:-32}" 2>/dev/null || head -c "$(( ${1:-32} * 2 ))" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

# ---------------------------------------------------------------- 1. 前置检查
say "1/6 检查前置条件"

[[ "$(uname -s)" == "Linux" ]] || die "本脚本只支持 Linux（当前 $(uname -s)）"
[[ "$(uname -m)" == "x86_64" ]] || warn "当前架构 $(uname -m)，镜像按 x86_64 构建"

if [[ "${EUID}" -eq 0 ]]; then
  DOCKER="docker"
elif docker info >/dev/null 2>&1; then
  DOCKER="docker"
elif sudo -n docker info >/dev/null 2>&1; then
  DOCKER="sudo -n docker"
else
  die "当前用户无法访问 Docker。请用 root 运行，或把用户加入 docker 组后重试"
fi

$DOCKER compose version >/dev/null 2>&1 || die "缺少 Docker Compose v2（需要 'docker compose' 子命令）"
ok "Docker 与 Compose v2 可用"

command -v curl >/dev/null 2>&1 || die "缺少 curl（用于健康检查）"
command -v openssl >/dev/null 2>&1 || die "缺少 openssl（用于生成密钥与自签证书）"

if [[ "$SKIP_PORT_CHECK" -eq 0 ]] && command -v ss >/dev/null 2>&1; then
  for port in 80 443; do
    if ss -ltn "sport = :$port" 2>/dev/null | grep -q LISTEN; then
      die "端口 $port 已被占用；先停掉占用它的服务（ss -ltnp 'sport = :$port'）"
    fi
  done
  ok "80 / 443 端口空闲"
fi

MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
if [[ "$MEM_MB" -gt 0 && "$MEM_MB" -lt 1900 ]]; then
  warn "内存仅 ${MEM_MB}MB，构建镜像可能被 OOM 杀掉；建议 4GB 以上"
fi
ok "内存 ${MEM_MB}MB，磁盘剩余 $(df -h "$ROOT" | awk 'NR==2 {print $4}')"

# ---------------------------------------------------------------- 2. 生成 .env
say "2/6 准备环境变量"

if [[ -f .env ]]; then
  ok ".env 已存在，保留不覆盖"
  # 从已有 .env 读取数据库密码，供后续备份命令使用
  DB_PASSWORD="$(grep -E '^POSTGRES_PASSWORD=' .env | head -1 | cut -d= -f2- || true)"
else
  DB_PASSWORD="$(rand_hex 24)"
  ADMIN_PASSWORD="$(rand_hex 8)"
  JWT_SECRET="$(rand_hex 32)"
  AI_KEY="$(rand_hex 32)"

  umask 077
  cat > .env <<EOF
# 由 scripts/install.sh 于 $(date -u +%Y-%m-%dT%H:%M:%SZ) 生成；请妥善保管，勿提交到 git。
POSTGRES_DB=paiban
POSTGRES_USER=paiban
POSTGRES_PASSWORD=${DB_PASSWORD}

PAIBAN_JWT_SECRET=${JWT_SECRET}
PAIBAN_AI_ENCRYPTION_KEY=${AI_KEY}
PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PASSWORD}

TZ=Asia/Shanghai
JAVA_OPTS=-XX:MaxRAMPercentage=75.0
EOF
  umask 022
  ok ".env 已生成（数据库密码、JWT、加密钥匙、初始管理员密码均为随机值）"
  echo "$ADMIN_PASSWORD" > .initial-admin-password
  chmod 600 .initial-admin-password
fi

# ---------------------------------------------------------------- 3. 证书
say "3/6 准备 HTTPS 证书"

mkdir -p deploy/certs
if [[ -s deploy/certs/fullchain.pem && -s deploy/certs/privkey.pem ]]; then
  ok "已有证书，直接使用（$(openssl x509 -in deploy/certs/fullchain.pem -noout -subject 2>/dev/null | head -1)）"
else
  CN="${DOMAIN:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
  [[ -n "$CN" ]] || CN="localhost"
  SAN="DNS:localhost,DNS:${CN}"
  if [[ "$CN" =~ ^[0-9.]+$ ]]; then
    SAN="IP:${CN},${SAN}"
  fi

  openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
    -keyout deploy/certs/privkey.pem \
    -out deploy/certs/fullchain.pem \
    -subj "/CN=${CN}" -addext "subjectAltName=${SAN}" >/dev/null 2>&1 \
    || die "自签证书生成失败"
  chmod 600 deploy/certs/privkey.pem
  warn "已生成自签证书（CN=${CN}）：浏览器会提示不安全，正式使用请换成真实证书后重启 nginx"
fi

# ---------------------------------------------------------------- 4. 构建与启动
say "4/6 构建并启动容器（首次约 5–10 分钟）"

COMPOSE="$DOCKER compose -f docker-compose.prod.yml"
if [[ "$DO_BUILD" -eq 1 ]]; then
  $COMPOSE up -d --build || die "构建或启动失败，查看：docker compose -f docker-compose.prod.yml logs --tail=100"
else
  $COMPOSE up -d || die "启动失败，查看：docker compose -f docker-compose.prod.yml logs --tail=100"
fi
ok "容器已启动"

# ---------------------------------------------------------------- 5. 等健康
say "5/6 等待服务就绪（最多 ${HEALTH_TIMEOUT}s）"

deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
until curl -kfsS https://127.0.0.1/api/health >/dev/null 2>&1; do
  if [[ "$(date +%s)" -ge "$deadline" ]]; then
    echo
    $COMPOSE ps || true
    die "健康检查超时；查看后端日志：docker compose -f docker-compose.prod.yml logs backend --tail=100"
  fi
  sleep 5
done
ok "后端健康检查通过"

for i in 1 2 3 4 5 6; do
  unhealthy=$($COMPOSE ps --format '{{.Name}} {{.Health}}' 2>/dev/null | grep -c unhealthy || true)
  [[ "$unhealthy" -eq 0 ]] && break
  sleep 5
done

# ---------------------------------------------------------------- 6. 汇总
say "6/6 安装完成"

HOST_FOR_URL="${DOMAIN:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
[[ -n "$HOST_FOR_URL" ]] || HOST_FOR_URL="127.0.0.1"

cat <<EOF

  访问地址   https://${HOST_FOR_URL}/     （自签证书需在浏览器点「继续访问」）

  管理员账号 admin
EOF

if [[ -f .initial-admin-password ]]; then
  printf '  管理员密码 %s\n' "$(cat .initial-admin-password)"
  printf '             （已同时写入 .initial-admin-password，登录后请立即修改并删除该文件）\n'
else
  printf '  管理员密码 见 .env 的 PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD\n'
fi

cat <<EOF

  常用命令
    状态      docker compose -f docker-compose.prod.yml ps
    日志      docker compose -f docker-compose.prod.yml logs -f backend
    重启      docker compose -f docker-compose.prod.yml restart
    停止      docker compose -f docker-compose.prod.yml down
    备份      COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh
    恢复      COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh --yes

  下一步
    1. 用浏览器登录，改掉初始密码
    2. 设置 → AI 供应商（如需 AI 导入/规则建议）
    3. 设置 → 钉钉集成（如需组织架构同步，先把本机出口 IP 加入钉钉应用白名单）
    4. 按需在初始化向导里建学期、导入教师/班级/课程

EOF
