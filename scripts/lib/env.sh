#!/usr/bin/env bash
# .env 引导助手，仅由 scripts/start.sh 使用（测试会直接 source 本文件）。
#
# 职责：
#   1. .env 不存在时从 .env.example 复制；
#   2. 只在值为空或仍是占位值时生成随机密钥，绝不覆盖已有的真实值；
#   3. 保证 docker-compose.yml 里 ${VAR:?err} 的变量在启动前一定存在。
#
# 对外函数：
#   paiban_ensure_env <repo_root> [admin_password] [quiet]
#     成功后设置 PAIBAN_ENV_FILE、PAIBAN_EFFECTIVE_ADMIN_PASSWORD 与
#     PAIBAN_ENV_CREATED（1 = 本次新建了 .env，0 = .env 原本就存在）。
#   paiban_env_get <file> <key>
#   paiban_env_set <file> <key> <value>
#   paiban_is_placeholder <value>
#   paiban_random_hex <byte_count>

_paiban_env_note() {
  # 静默模式下不输出进度信息；警告/错误由调用方直接写 stderr。
  if [ "${PAIBAN_ENV_QUIET:-0}" != "1" ]; then
    printf '%s\n' "$1"
  fi
}

paiban_random_hex() {
  local bytes="${1:-32}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
    return 0
  fi
  if command -v od >/dev/null 2>&1 && [ -r /dev/urandom ]; then
    od -An -tx1 -N "$bytes" /dev/urandom | tr -d ' \n'
    return 0
  fi
  return 1
}

paiban_is_placeholder() {
  local value="${1:-}"
  local lower
  if [ -z "$value" ]; then
    return 0
  fi
  lower="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
  case "$lower" in
    replace-with* | replace_with* | change-me* | changeme* | your-* | your_* | todo*)
      return 0
      ;;
  esac
  return 1
}

paiban_env_get() {
  local file="$1"
  local key="$2"
  [ -f "$file" ] || return 1
  awk -F= -v k="$key" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$file" | tr -d '\r'
}

paiban_env_set() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp
  tmp="$(mktemp "${file}.XXXXXX")"
  awk -v k="$key" -v v="$value" '
    {
      sub(/\r$/, "")
      if (!done && index($0, k "=") == 1) {
        print k "=" v
        done = 1
      } else {
        print
      }
    }
    END {
      if (!done) {
        print k "=" v
      }
    }
  ' "$file" > "$tmp"
  mv "$tmp" "$file"
}

_paiban_ensure_secret() {
  local key="$1"
  local bytes="$2"
  local label="$3"
  local current new

  current="$(paiban_env_get "$PAIBAN_ENV_FILE" "$key" || true)"
  if ! paiban_is_placeholder "$current"; then
    return 0
  fi

  if ! new="$(paiban_random_hex "$bytes")"; then
    printf '[错误] 无法生成随机密钥（缺少 openssl 或 /dev/urandom）：%s\n' "$key" >&2
    return 1
  fi
  paiban_env_set "$PAIBAN_ENV_FILE" "$key" "$new"
  _paiban_env_note "[信息] 已生成 ${label}：$(printf '%s' "$new" | cut -c1-4)****"
}

paiban_ensure_env() {
  local root="${1:-.}"
  local admin="${2:-}"
  local quiet="${3:-0}"
  local env_file="$root/.env"
  local example_file="$root/.env.example"
  local current_admin effective_admin

  PAIBAN_ENV_QUIET="$quiet"
  PAIBAN_ENV_CREATED=0

  if [ ! -f "$env_file" ]; then
    if [ ! -f "$example_file" ]; then
      printf '[错误] 缺少 .env.example：%s\n' "$example_file" >&2
      return 1
    fi
    cp "$example_file" "$env_file"
    PAIBAN_ENV_CREATED=1
    _paiban_env_note '[信息] 未找到 .env，已从 .env.example 创建。'
  fi

  PAIBAN_ENV_FILE="$env_file"

  _paiban_ensure_secret PAIBAN_JWT_SECRET 32 'JWT 签名密钥' || return 1
  _paiban_ensure_secret PAIBAN_AI_ENCRYPTION_KEY 32 'AI 加密密钥' || return 1
  _paiban_ensure_secret POSTGRES_PASSWORD 16 '数据库密码' || return 1

  # 初始管理员密码：显式参数优先；否则保留已有真实值；再否则默认 admin123。
  current_admin="$(paiban_env_get "$env_file" PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD || true)"
  if [ -n "$admin" ]; then
    effective_admin="$admin"
    if [ "$admin" != "$current_admin" ]; then
      paiban_env_set "$env_file" PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD "$admin"
      _paiban_env_note '[信息] 已按 --admin-password 设置初始管理员密码。'
    fi
  elif [ -n "$current_admin" ] && ! paiban_is_placeholder "$current_admin"; then
    effective_admin="$current_admin"
  else
    effective_admin='admin123'
    paiban_env_set "$env_file" PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD "$effective_admin"
    _paiban_env_note "[信息] 已设置初始管理员密码：admin / $effective_admin"
  fi

  PAIBAN_EFFECTIVE_ADMIN_PASSWORD="$effective_admin"
  return 0
}
