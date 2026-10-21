#!/bin/sh
# 容器内密钥自愈（deploy-friendly ticket 03）。
#
# 由 entrypoint.sh 在启动 java 之前 source（因此 export 的值会被 java 继承）；
# 也可以直接执行，用于测试或手工排查。
#
# 规则（两个密钥一致处理）：
#   1. 环境变量非空 -> 原样保留，绝不覆盖运维显式提供的值；
#   2. 否则读取 $PAIBAN_DATA_DIR/secrets.env（KEY=VALUE）；
#   3. 仍然缺失 -> 生成 64 位十六进制随机串，追加写入 secrets.env（chmod 600）；
#   4. 日志只打印前 4 位 + ****，绝不打印完整密钥。
#
# 幂等：第二次启动复用 secrets.env 里的值，不会再生成新密钥。
#
# 可调变量：PAIBAN_DATA_DIR（默认 /app/data，测试时指向临时目录）。

# entrypoint.sh 带着 set -e 运行；本脚本自行处理错误，结束时恢复调用方的设置。
case $- in
  *e*)
    _paiban_bootstrap_restore_errexit=1
    set +e
    ;;
  *)
    _paiban_bootstrap_restore_errexit=0
    ;;
esac

PAIBAN_DATA_DIR="${PAIBAN_DATA_DIR:-/app/data}"
PAIBAN_SECRETS_FILE="$PAIBAN_DATA_DIR/secrets.env"

_paiban_hex64() {
  _paiban_hex=''
  if command -v openssl >/dev/null 2>&1; then
    _paiban_hex="$(openssl rand -hex 32 2>/dev/null || true)"
  fi
  if [ -z "$_paiban_hex" ] && [ -r /dev/urandom ] && command -v od >/dev/null 2>&1; then
    _paiban_hex="$(od -An -tx1 -N 32 /dev/urandom 2>/dev/null | tr -d ' \n' || true)"
  fi
  if [ -z "$_paiban_hex" ]; then
    return 1
  fi
  printf '%s' "$_paiban_hex"
}

# secrets.env 里 KEY 的最后一个非空值；文件或键不存在时输出空串。
_paiban_secret_get() {
  if [ ! -f "$PAIBAN_SECRETS_FILE" ]; then
    return 0
  fi
  awk -F= -v k="$1" '
    $1 == k {
      sub(/^[^=]*=/, "")
      sub(/\r$/, "")
      if ($0 != "") { value = $0 }
    }
    END { printf "%s", value }
  ' "$PAIBAN_SECRETS_FILE" 2>/dev/null
}

_paiban_secret_put() {
  printf '%s=%s\n' "$1" "$2" >> "$PAIBAN_SECRETS_FILE" || return 1
  chmod 600 "$PAIBAN_SECRETS_FILE" 2>/dev/null || true
  return 0
}

_paiban_mask() {
  _paiban_prefix="$(printf '%s' "$1" | cut -c1-4)"
  printf '%s****' "${_paiban_prefix:-?}"
}

_paiban_fail() {
  printf '[初始化][错误] %s\n' "$1" >&2
  exit 1
}

# 解析一个密钥：环境变量 -> secrets.env -> 新生成；结果写入同名的环境变量。
_paiban_resolve_secret() {
  _paiban_key="$1"
  _paiban_label="$2"
  _paiban_value=''

  # printenv 不会重新解析值里的空格/引号；缺失时退回带引号的 eval。
  if command -v printenv >/dev/null 2>&1; then
    _paiban_value="$(printenv "$_paiban_key" 2>/dev/null || true)"
  else
    eval "_paiban_value=\"\${$_paiban_key:-}\""
  fi

  if [ -n "$_paiban_value" ]; then
    printf '[初始化] 使用环境变量提供的 %s（前缀 %s）\n' \
      "$_paiban_label" "$(_paiban_mask "$_paiban_value")"
    export "$_paiban_key=$_paiban_value"
    return 0
  fi

  _paiban_value="$(_paiban_secret_get "$_paiban_key")"
  if [ -n "$_paiban_value" ]; then
    printf '[初始化] 已复用 %s 中的 %s（前缀 %s）\n' \
      "$PAIBAN_SECRETS_FILE" "$_paiban_label" "$(_paiban_mask "$_paiban_value")"
    export "$_paiban_key=$_paiban_value"
    return 0
  fi

  if ! _paiban_value="$(_paiban_hex64)"; then
    _paiban_fail "无法生成随机密钥（缺少 openssl 与 /dev/urandom）：$_paiban_key"
  fi
  if ! _paiban_secret_put "$_paiban_key" "$_paiban_value"; then
    _paiban_fail "无法写入密钥文件：$PAIBAN_SECRETS_FILE"
  fi
  printf '[初始化] 已生成 %s 并写入 %s（前缀 %s，仅显示一次）\n' \
    "$_paiban_label" "$PAIBAN_SECRETS_FILE" "$(_paiban_mask "$_paiban_value")"
  export "$_paiban_key=$_paiban_value"
  return 0
}

mkdir -p "$PAIBAN_DATA_DIR" 2>/dev/null
if [ ! -d "$PAIBAN_DATA_DIR" ]; then
  _paiban_fail "无法创建数据目录：$PAIBAN_DATA_DIR"
fi
if [ -f "$PAIBAN_SECRETS_FILE" ]; then
  chmod 600 "$PAIBAN_SECRETS_FILE" 2>/dev/null || true
fi

_paiban_resolve_secret PAIBAN_JWT_SECRET 'JWT Secret'
_paiban_resolve_secret PAIBAN_AI_ENCRYPTION_KEY 'AI 加密密钥'

if [ "$_paiban_bootstrap_restore_errexit" = "1" ]; then
  set -e
fi

unset _paiban_key _paiban_label _paiban_value _paiban_hex _paiban_prefix
