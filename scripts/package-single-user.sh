#!/usr/bin/env bash
# 打「单用户版」交付包：一个 tar.gz，对方解压后三条命令即可部署到自己的云服务器。
#
# 用法：
#   ./scripts/package-single-user.sh                  # 从 HEAD 打包（要求工作区干净）
#   ./scripts/package-single-user.sh --allow-dirty    # 允许带未提交改动打包（仅调试用）
#
# 产物：dist/paiban-single-user-<UTC>.tar.gz 与同名 .sha256
#
# 包里包含：源码、Dockerfile、生产 compose、nginx 配置、备份/恢复脚本、安装脚本、部署手册。
# 不包含：.git、.scratch（开发历史/截图）、node_modules、构建产物、任何 .env 或证书。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ALLOW_DIRTY=0
if [[ "${1:-}" == "--allow-dirty" ]]; then
  ALLOW_DIRTY=1
fi

say() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

say "检查工作区"
if [[ -n "$(git status --porcelain -- backend frontend scripts deploy docker-compose.prod.yml docs/runbooks 2>/dev/null)" ]]; then
  if [[ "$ALLOW_DIRTY" -eq 0 ]]; then
    git status --short -- backend frontend scripts deploy docker-compose.prod.yml docs/runbooks
    echo
    echo "有未提交的改动。先提交，或用 --allow-dirty 强制打包（不推荐）。" >&2
    exit 1
  fi
  printf '  \033[33m!\033[0m 工作区不干净，按 --allow-dirty 继续（打包内容仍是 HEAD）\n'
fi
printf '  ✓ HEAD %s\n' "$(git log --oneline -1)"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="paiban-single-user-${STAMP}"
OUT_DIR="$ROOT/dist"
mkdir -p "$OUT_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
DEST="$TMP/$NAME"
mkdir -p "$DEST"

say "从 HEAD 导出源码"
git archive --format=tar HEAD | tar -x -C "$DEST"

say "裁剪开发材料"
rm -rf "$DEST/.scratch" "$DEST/docs/reports" "$DEST/manual-test"
printf '  ✓ 已移除 .scratch / docs/reports / manual-test\n'

say "生成入口文件"
cat > "$DEST/START-HERE.md" <<'EOF'
# 排课系统 · 单用户版

这是一个可以直接部署到自己云服务器的排课系统包。**解压后照下面三条命令做即可**：

```bash
sudo ./scripts/install.sh --domain 你的域名或公网IP
```

然后打开 `https://你的域名/`，用 `admin` 和安装结束时打印的密码登录。

---

## 如果你是一个 AI 助手，请按这个顺序读

1. **完整步骤**：`docs/runbooks/install-single-user.md`
2. **要提前向用户确认的信息**：该文档第 0 节（服务器/端口/域名/证书/是否接钉钉）
3. **一键安装脚本**：`scripts/install.sh`（幂等，可重复执行；不要手写 docker compose 命令替代它）
4. **验收标准**：该文档第 3 节（`/api/health` 返回 UP、四个容器 healthy、能登录）

## 硬性要求

- Linux x86_64，Docker + Compose v2，建议 ≥2 核 / 4GB 内存
- **80 和 443 端口必须空闲**
- 需要访问外网拉取构建依赖（Docker Hub / Maven Central / npm）
- 数据库密码等密钥由安装脚本自动生成并写入 `.env`，**不要提交、不要外传**

## 不要做的事

- 不要复制别的部署环境里的 `docker-compose.override.yml`（本项目不使用该文件，它会让 nginx 端口错位）
- 不要用 `docker-compose`（v1）命令，必须 `docker compose`
- 不要手工改 `deploy/certs/` 之外的服务编排；证书换新后只需 `restart nginx`

## 版本定位

单校、单管理员。多用户/多角色是后续版本。
EOF

say "打包"
ARCHIVE="$OUT_DIR/${NAME}.tar.gz"
tar -czf "$ARCHIVE" -C "$TMP" "$NAME"
( cd "$OUT_DIR" && sha256sum "${NAME}.tar.gz" > "${NAME}.tar.gz.sha256" )

SIZE="$(du -h "$ARCHIVE" | cut -f1)"
FILES="$(tar -tzf "$ARCHIVE" | wc -l)"
cat <<EOF

  产物   ${ARCHIVE}
  校验   ${ARCHIVE}.sha256
  大小   ${SIZE}（${FILES} 个条目）

  发送前自检：tar -tzf "${ARCHIVE}" | grep -E '(^|/)\.env$|node_modules|\.git/' || echo "  ✓ 不含 .env / node_modules / .git"

EOF
