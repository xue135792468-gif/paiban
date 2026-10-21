#!/usr/bin/env bash
# doctor 自检命令的回归测试：语法、JSON 结构、退出码、人类可读模式。
# 用法：bash scripts/tests/test-doctor.sh
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
ROOT="$(pwd)"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf 'ok   - %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf 'FAIL - %s\n' "$1"; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/validate.py" <<'PY'
import json
import sys


def die(msg):
    print("ASSERT_FAIL: " + msg)
    sys.exit(1)


raw = sys.stdin.read()
if not raw.strip():
    die("stdout is empty")

try:
    data = json.loads(raw)
except Exception as exc:  # noqa: BLE001 - report anything the tool printed
    die("stdout is not valid JSON (%s): %r" % (exc, raw[:400]))

if not isinstance(data, dict):
    die("top level is not an object")

for key in ("docker", "ports", "memory", "database", "disk", "exit_code"):
    if key not in data:
        die("missing key: " + key)

docker = data["docker"]
if not isinstance(docker, dict):
    die("docker is not an object")
if not isinstance(docker.get("installed"), bool):
    die("docker.installed is not a bool")
if docker.get("version") is not None and not isinstance(docker.get("version"), str):
    die("docker.version is not string|null")
if not isinstance(docker.get("running"), bool):
    die("docker.running is not a bool")

ports = data["ports"]
if not isinstance(ports, dict):
    die("ports is not an object")
for port in ("8080", "5173", "5432"):
    if ports.get(port) not in ("free", "occupied"):
        die("ports.%s is not free|occupied: %r" % (port, ports.get(port)))

memory = data["memory"]
for key in ("total_gb", "available_gb"):
    value = memory.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        die("memory.%s is not a number: %r" % (key, value))
if not isinstance(memory.get("sufficient"), bool):
    die("memory.sufficient is not a bool")

database = data["database"]
if not isinstance(database.get("reachable"), bool):
    die("database.reachable is not a bool")
if not isinstance(database.get("migrated"), bool):
    die("database.migrated is not a bool")
if database.get("flyway_version") is not None and not isinstance(
    database.get("flyway_version"), str
):
    die("database.flyway_version is not string|null")

disk = data["disk"]
e_free = disk.get("e_drive_free_gb")
if e_free is not None and (isinstance(e_free, bool) or not isinstance(e_free, (int, float))):
    die("disk.e_drive_free_gb is not number|null: %r" % (e_free,))
if not isinstance(disk.get("sufficient"), bool):
    die("disk.sufficient is not a bool")

code = data["exit_code"]
if isinstance(code, bool) or code not in (0, 1, 2):
    die("exit_code is not 0|1|2: %r" % (code,))

print("exit_code=%d" % code)
PY

# check_json <label> <command...>
# Runs the command, validates stdout as the doctor JSON contract and asserts the
# JSON exit_code equals the process exit code. Leaves JSON in LAST_JSON / LAST_CODE.
check_json() {
  local label="$1"
  shift
  local out code verdict vcode

  out="$("$@" 2>/dev/null)"
  code=$?

  verdict="$(printf '%s' "$out" | python "$TMP_DIR/validate.py" 2>&1)"
  vcode=$?

  if [[ $vcode -ne 0 ]]; then
    fail "$label: JSON contract violated -> $verdict"
    printf '     raw stdout: %s\n' "$(printf '%s' "$out" | head -c 400)"
    LAST_JSON=""
    LAST_CODE="$code"
    return 1
  fi

  local json_code="${verdict#exit_code=}"
  if [[ "$json_code" != "$code" ]]; then
    fail "$label: exit_code in JSON ($json_code) != process exit ($code)"
  else
    pass "$label: valid JSON, exit_code=$json_code matches process exit"
  fi

  LAST_JSON="$out"
  LAST_CODE="$code"
  return 0
}

echo "== bash syntax =="
for script in doctor.sh scripts/doctor.sh scripts/tests/test-doctor.sh; do
  if bash -n "$script" 2>/dev/null; then
    pass "bash -n $script"
  else
    fail "bash -n $script"
  fi
done

echo "== bash doctor --json =="
if [[ -x ./doctor.sh ]]; then
  check_json "./doctor.sh --json" ./doctor.sh --json
else
  fail "./doctor.sh is not executable"
  check_json "./doctor.sh --json (via bash)" bash ./doctor.sh --json
fi
SH_JSON="$LAST_JSON"
SH_CODE="$LAST_CODE"

echo "== bash doctor --quiet (JSON, same contract) =="
check_json "./doctor.sh --quiet" ./doctor.sh --quiet

echo "== bash doctor --help =="
help_out="$(./doctor.sh --help 2>&1)"
help_code=$?
if [[ $help_code -eq 0 && "$help_out" == *"--json"* ]]; then
  pass "./doctor.sh --help exits 0 and documents --json"
else
  fail "./doctor.sh --help (exit=$help_code)"
fi

echo "== bash doctor human mode =="
human_out="$(./doctor.sh 2>/dev/null)"
human_code=$?
if [[ $human_code -eq "$SH_CODE" ]]; then
  pass "human mode exit ($human_code) matches JSON exit ($SH_CODE)"
else
  fail "human mode exit ($human_code) != JSON exit ($SH_CODE)"
fi
if [[ "$human_out" == *"exit_code="* ]]; then
  pass "human mode prints a recognizable summary"
else
  fail "human mode summary missing (exit_code= marker)"
  printf '     raw stdout: %s\n' "$(printf '%s' "$human_out" | head -c 400)"
fi
if printf '%s' "$human_out" | grep -q $'\033'; then
  fail "human mode emitted ANSI escapes while stdout is not a TTY"
else
  pass "human mode has no ANSI escapes when stdout is not a TTY"
fi

echo "== exit-code contract (static) =="
# A healthy running stack occupies 8080/5173/5432, so port occupancy must never
# influence the exit code. Extract the exit-code blocks and prove they only
# reference docker/database/disk (2) and memory/migrations (1).
sh_exit_block="$(awk '/^# --- 退出码/{flag=1;next}/^# --- 输出/{flag=0}flag' scripts/doctor.sh)"
if [[ -z "$sh_exit_block" ]]; then
  fail "scripts/doctor.sh has a recognizable exit-code block"
else
  if printf '%s' "$sh_exit_block" | grep -Eq 'port_8080|port_5173|port_5432|ports'; then
    fail "doctor.sh exit code ignores port occupancy"
  else
    pass "doctor.sh exit code ignores port occupancy"
  fi
  if printf '%s' "$sh_exit_block" | grep -q 'db_reachable' &&
    printf '%s' "$sh_exit_block" | grep -q 'disk_sufficient'; then
    pass "doctor.sh exit 2 covers database/disk"
  else
    fail "doctor.sh exit 2 covers database/disk"
  fi
  if printf '%s' "$sh_exit_block" | grep -q 'mem_sufficient' &&
    printf '%s' "$sh_exit_block" | grep -q 'db_migrated'; then
    pass "doctor.sh exit 1 covers memory/migrations"
  else
    fail "doctor.sh exit 1 covers memory/migrations"
  fi
fi

ps_exit_block="$(awk '/^# --- 退出码/{flag=1;next}/^# --- 输出/{flag=0}flag' scripts/doctor.ps1)"
if [[ -z "$ps_exit_block" ]]; then
  fail "scripts/doctor.ps1 has a recognizable exit-code block"
else
  if printf '%s' "$ps_exit_block" | grep -Eq '\$port8080|\$port5173|\$port5432'; then
    fail "doctor.ps1 exit code ignores port occupancy"
  else
    pass "doctor.ps1 exit code ignores port occupancy"
  fi
fi

if grep -q '若为本系统容器则正常' scripts/doctor.sh; then
  pass "doctor.sh labels occupied ports as informational"
else
  fail "doctor.sh labels occupied ports as informational"
fi
if grep -q '若为本系统容器则正常' scripts/doctor.ps1; then
  pass "doctor.ps1 labels occupied ports as informational"
else
  fail "doctor.ps1 labels occupied ports as informational"
fi

echo "== docker-less host assertions =="
if command -v docker >/dev/null 2>&1; then
  echo "skip - docker is installed on this host; absence assertions do not apply"
else
  if [[ "$SH_CODE" -eq 2 ]]; then
    pass "without docker, exit code is 2"
  else
    fail "without docker, exit code is $SH_CODE (expected 2)"
  fi
  installed="$(printf '%s' "$SH_JSON" | python -c 'import json,sys; print(str(json.load(sys.stdin)["docker"]["installed"]).lower())' 2>/dev/null)"
  if [[ "$installed" == "false" ]]; then
    pass "without docker, docker.installed is false"
  else
    fail "without docker, docker.installed is '$installed' (expected false)"
  fi
fi

echo "== powershell doctor --json =="
if command -v powershell >/dev/null 2>&1; then
  check_json "scripts/doctor.ps1 --json" powershell -NoProfile -ExecutionPolicy Bypass -File scripts/doctor.ps1 --json
  PS_JSON="$LAST_JSON"
  PS_CODE="$LAST_CODE"

  if command -v docker >/dev/null 2>&1; then
    echo "skip - docker is installed; powershell absence assertion does not apply"
  else
    if [[ "$PS_CODE" -eq 2 ]]; then
      pass "powershell without docker: exit code is 2"
    else
      fail "powershell without docker: exit code is $PS_CODE (expected 2)"
    fi
    installed="$(printf '%s' "$PS_JSON" | python -c 'import json,sys; print(str(json.load(sys.stdin)["docker"]["installed"]).lower())' 2>/dev/null)"
    if [[ "$installed" == "false" ]]; then
      pass "powershell without docker: docker.installed is false"
    else
      fail "powershell without docker: docker.installed is '$installed' (expected false)"
    fi
  fi

  ps_human="$(powershell -NoProfile -ExecutionPolicy Bypass -File scripts/doctor.ps1 2>/dev/null)"
  ps_human_code=$?
  if [[ $ps_human_code -eq "$PS_CODE" && "$ps_human" == *"exit_code="* ]]; then
    pass "powershell human mode exits $ps_human_code with summary"
  else
    fail "powershell human mode (exit=$ps_human_code, expected $PS_CODE with summary)"
  fi
else
  echo "skip - powershell not available on this host"
fi

echo
printf 'summary: %d passed, %d failed\n' "$PASS" "$FAIL"
[[ $FAIL -eq 0 ]]
