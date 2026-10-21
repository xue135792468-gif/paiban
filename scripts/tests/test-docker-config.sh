#!/usr/bin/env bash
# Static verification of Docker readiness configuration (deploy-friendly ticket 04).
#
# Checks the compose healthchecks / depends_on wiring and the backend image
# HEALTHCHECK without needing Docker: Docker is only used for an extra
# `docker compose config -q` syntax check when it happens to be installed.
#
# Usage: scripts/tests/test-docker-config.sh
# Exit:  0 = all assertions pass (Docker absence is a skip, not a failure)
#        1 = at least one assertion failed

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
BACKEND_DOCKERFILE="$ROOT_DIR/backend/Dockerfile"

failures=0

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }

echo "== Docker readiness config =="

if [ ! -f "$COMPOSE_FILE" ]; then
  fail "docker-compose.yml exists ($COMPOSE_FILE)"
elif [ ! -f "$BACKEND_DOCKERFILE" ]; then
  fail "backend/Dockerfile exists ($BACKEND_DOCKERFILE)"
else
  # All compose / Dockerfile assertions live in one Python pass so each one
  # reports its own PASS/FAIL line.
  python - "$COMPOSE_FILE" "$BACKEND_DOCKERFILE" <<'PY'
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - environment guard
    print("FAIL  PyYAML is installed (pip install pyyaml)")
    sys.exit(1)

compose_path, dockerfile_path = sys.argv[1], sys.argv[2]
failures = 0


def check(ok, label, detail=""):
    global failures
    if ok:
        print(f"PASS  {label}")
    else:
        failures += 1
        print(f"FAIL  {label}" + (f" ({detail})" if detail else ""))


with open(compose_path, encoding="utf-8") as fh:
    compose = yaml.safe_load(fh) or {}
services = compose.get("services") or {}


def healthcheck_test(service):
    """Return the service healthcheck test as one string, or '' if absent."""
    healthcheck = (services.get(service) or {}).get("healthcheck") or {}
    test = healthcheck.get("test")
    if isinstance(test, list):
        return " ".join(str(part) for part in test)
    return test or ""


def depends_on_condition(service, dependency):
    """Return depends_on.<dependency>.condition, or None if not declared."""
    depends_on = (services.get(service) or {}).get("depends_on") or {}
    if isinstance(depends_on, list):
        return None
    entry = depends_on.get(dependency)
    return entry.get("condition") if isinstance(entry, dict) else None


postgres_test = healthcheck_test("postgres")
check("pg_isready" in postgres_test, "postgres healthcheck uses pg_isready", postgres_test)

backend_condition = depends_on_condition("backend", "postgres")
check(
    backend_condition == "service_healthy",
    "backend waits for postgres with condition service_healthy",
    f"got {backend_condition!r}",
)

backend_test = healthcheck_test("backend")
check("/api/health" in backend_test, "backend healthcheck targets /api/health", backend_test)

frontend_condition = depends_on_condition("frontend", "backend")
check(
    frontend_condition == "service_healthy",
    "frontend waits for backend with condition service_healthy",
    f"got {frontend_condition!r}",
)

frontend_healthcheck = (services.get("frontend") or {}).get("healthcheck") or {}
check(bool(frontend_healthcheck), "frontend declares a healthcheck")

with open(dockerfile_path, encoding="utf-8") as fh:
    dockerfile = fh.read()

# Join Dockerfile line continuations so a multi-line HEALTHCHECK is one string.
joined = re.sub(r"\\\s*\n\s*", " ", dockerfile)
healthchecks = [
    line.strip()
    for line in joined.splitlines()
    if line.strip().upper().startswith("HEALTHCHECK")
]
check(
    any("/api/health" in line for line in healthchecks),
    "backend/Dockerfile HEALTHCHECK targets /api/health",
    "; ".join(healthchecks) or "no HEALTHCHECK instruction",
)
check(
    any(re.search(r"--start-period=60s\b", line) for line in healthchecks),
    "backend/Dockerfile HEALTHCHECK sets start-period=60s",
    "; ".join(healthchecks) or "no HEALTHCHECK instruction",
)

# Exit code carries the failure count so the bash summary stays accurate.
sys.exit(min(failures, 125))
PY
  failures=$((failures + $?))
fi

# Extra schema check only when Docker is present; absence is a skip, not a failure.
if command -v docker >/dev/null 2>&1; then
  # Dummy secrets keep this a schema check rather than a secret-presence check.
  if (cd "$ROOT_DIR" && \
      PAIBAN_JWT_SECRET="static-check-only-static-check-only" \
      PAIBAN_AI_ENCRYPTION_KEY="static-check-only" \
      docker compose config -q); then
    pass "docker compose config -q"
  else
    fail "docker compose config -q"
  fi
else
  echo "SKIP  docker compose config -q (docker not installed)"
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "RESULT: PASS"
  exit 0
fi

echo "RESULT: FAIL ($failures failing assertion(s))"
exit 1
