#!/usr/bin/env bash
# Static verification of the production deployment stack (deploy-friendly ticket 06).
#
# Covers:
#   - docker-compose.prod.yml topology (ports, mounts, healthchecks, secrets)
#   - deploy/nginx/conf.d/paiban.conf TLS reverse proxy
#   - scripts/restore.sh / scripts/backup.sh syntax and non-interactive safety
#   - chore/prod-hardening ancestry (its work is merged, nothing left to merge)
#
# Docker is never required: when it is installed an extra
# `docker compose -f docker-compose.prod.yml config -q` schema check runs,
# otherwise it is reported as SKIP.
#
# Usage: scripts/tests/test-prod-config.sh
# Exit:  0 = all assertions pass (Docker absence is a skip, not a failure)
#        1 = at least one assertion failed

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.prod.yml"
NGINX_CONF="$ROOT_DIR/deploy/nginx/conf.d/paiban.conf"
BACKUP_SH="$ROOT_DIR/scripts/backup.sh"
RESTORE_SH="$ROOT_DIR/scripts/restore.sh"

failures=0

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }

echo "== 1. production compose topology =="

if [ ! -f "$COMPOSE_FILE" ]; then
  fail "docker-compose.prod.yml exists ($COMPOSE_FILE)"
else
  python - "$COMPOSE_FILE" <<'PY'
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - environment guard
    print("FAIL  PyYAML is installed (pip install pyyaml)")
    sys.exit(1)

compose_path = sys.argv[1]
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
required = {"postgres", "backend", "frontend", "nginx"}
check(required <= set(services), "services postgres/backend/frontend/nginx exist", ", ".join(sorted(services)))


def service(name):
    return services.get(name) or {}


def published_ports(name):
    """Host ports published by a service."""
    ports = service(name).get("ports") or []
    result = set()
    for entry in ports:
        if isinstance(entry, dict):
            value = entry.get("published", entry.get("target"))
            if value is not None:
                result.add(str(value))
        else:
            text = str(entry)
            parts = text.split(":")
            result.add(parts[-1].split("/")[0])
    return result


def mounts(name):
    """(source, target, read_only) triples for a service's volumes."""
    result = []
    for entry in service(name).get("volumes") or []:
        if isinstance(entry, dict):
            result.append(
                (
                    str(entry.get("source", "")),
                    str(entry.get("target", "")),
                    bool(entry.get("read_only", False)),
                )
            )
        else:
            parts = str(entry).split(":")
            source = parts[0] if len(parts) >= 2 else ""
            target = parts[1] if len(parts) >= 2 else parts[0]
            ro = len(parts) >= 3 and "ro" in parts[2].split(",")
            result.append((source, target, ro))
    return result


def mount_for(name, target):
    return next((m for m in mounts(name) if m[1] == target), None)


def healthcheck_test(name):
    test = (service(name).get("healthcheck") or {}).get("test")
    if isinstance(test, list):
        return " ".join(str(part) for part in test)
    return test or ""


def depends_condition(name, dependency):
    depends = service(name).get("depends_on") or {}
    if isinstance(depends, list):
        return None
    entry = depends.get(dependency)
    return entry.get("condition") if isinstance(entry, dict) else None


# --- published ports -------------------------------------------------------
nginx_ports = published_ports("nginx")
check({"80", "443"} <= nginx_ports, "nginx publishes 80 and 443", sorted(nginx_ports))
for name in ("postgres", "backend", "frontend"):
    check(not published_ports(name), f"{name} publishes no host port", published_ports(name))

# --- nginx mounts ----------------------------------------------------------
nginx_conf_mount = mount_for("nginx", "/etc/nginx/conf.d")
check(
    nginx_conf_mount is not None
    and "deploy/nginx/conf.d" in nginx_conf_mount[0]
    and nginx_conf_mount[2],
    "nginx mounts ./deploy/nginx/conf.d read-only",
    nginx_conf_mount,
)
certs_mount = mount_for("nginx", "/etc/nginx/certs")
check(
    certs_mount is not None and "deploy/certs" in certs_mount[0] and certs_mount[2],
    "nginx mounts ./deploy/certs read-only",
    certs_mount,
)
logs_mount = mount_for("nginx", "/var/log/nginx")
check(
    logs_mount is not None and "logs/nginx" in logs_mount[0] and not logs_mount[2],
    "nginx mounts ./logs/nginx read-write",
    logs_mount,
)

# --- dependency ordering ---------------------------------------------------
check(
    depends_condition("backend", "postgres") == "service_healthy",
    "backend waits for postgres service_healthy",
    depends_condition("backend", "postgres"),
)
check(
    depends_condition("frontend", "backend") == "service_healthy",
    "frontend waits for backend service_healthy",
    depends_condition("frontend", "backend"),
)
check(
    depends_condition("nginx", "frontend") == "service_healthy",
    "nginx waits for frontend service_healthy",
    depends_condition("nginx", "frontend"),
)

# --- healthchecks ----------------------------------------------------------
for name in ("postgres", "backend", "frontend", "nginx"):
    check(bool(service(name).get("healthcheck")), f"{name} declares a healthcheck")
check("pg_isready" in healthcheck_test("postgres"), "postgres healthcheck uses pg_isready", healthcheck_test("postgres"))
check("/api/health" in healthcheck_test("backend"), "backend healthcheck targets /api/health", healthcheck_test("backend"))
check("/healthz" in healthcheck_test("nginx"), "nginx healthcheck targets /healthz", healthcheck_test("nginx"))

# --- persistence -----------------------------------------------------------
declared_volumes = compose.get("volumes") or {}
check(bool(declared_volumes), "top-level named volumes are declared")
pg_data = mount_for("postgres", "/var/lib/postgresql/data")
check(
    pg_data is not None and pg_data[0] in declared_volumes,
    "postgres uses a named volume for /var/lib/postgresql/data",
    pg_data,
)

# --- log rotation ----------------------------------------------------------
for name in ("postgres", "backend", "frontend", "nginx"):
    options = ((service(name).get("logging") or {}).get("options")) or {}
    check(
        bool(options.get("max-size")) and bool(options.get("max-file")),
        f"{name} rotates logs (max-size + max-file)",
        options,
    )

# --- network ---------------------------------------------------------------
networks = compose.get("networks") or {}
check(bool(networks), "a compose network is declared", sorted(networks))
for name in ("postgres", "backend", "frontend", "nginx"):
    attached = service(name).get("networks") or {}
    if isinstance(attached, list):
        attached = {item: None for item in attached}
    check(
        bool(set(attached) & set(networks)),
        f"{name} joins the declared network",
        sorted(attached),
    )

# --- backend wiring --------------------------------------------------------
backend_env = service("backend").get("environment") or {}
if isinstance(backend_env, list):
    backend_env = dict(item.split("=", 1) for item in backend_env if "=" in item)
check(
    backend_env.get("SPRING_PROFILES_ACTIVE") == "postgres",
    "backend runs the postgres profile",
    backend_env.get("SPRING_PROFILES_ACTIVE"),
)
check(
    "postgres:5432" in str(backend_env.get("SPRING_DATASOURCE_URL", "")),
    "backend datasource points at postgres:5432",
    backend_env.get("SPRING_DATASOURCE_URL"),
)

# --- secret policy ---------------------------------------------------------
postgres_env = service("postgres").get("environment") or {}
if isinstance(postgres_env, list):
    postgres_env = dict(item.split("=", 1) for item in postgres_env if "=" in item)
check(
    "${POSTGRES_PASSWORD:?" in str(postgres_env.get("POSTGRES_PASSWORD", "")),
    "production postgres password is required (${POSTGRES_PASSWORD:?...})",
    postgres_env.get("POSTGRES_PASSWORD"),
)
check(
    str(backend_env.get("PAIBAN_JWT_SECRET", "missing")) == "${PAIBAN_JWT_SECRET:-}",
    "JWT secret defaults empty so the entrypoint can self-heal",
    backend_env.get("PAIBAN_JWT_SECRET"),
)
check(
    str(backend_env.get("PAIBAN_AI_ENCRYPTION_KEY", "missing")) == "${PAIBAN_AI_ENCRYPTION_KEY:-}",
    "AI key defaults empty so the entrypoint can self-heal",
    backend_env.get("PAIBAN_AI_ENCRYPTION_KEY"),
)

sys.exit(min(failures, 125))
PY
  failures=$((failures + $?))
fi

echo
echo "== 2. production nginx reverse proxy =="

if [ ! -f "$NGINX_CONF" ]; then
  fail "deploy/nginx/conf.d/paiban.conf exists ($NGINX_CONF)"
else
  # Collapse whitespace so aligned directives (extra spaces) still match.
  conf="$(sed 's/[[:space:]][[:space:]]*/ /g' "$NGINX_CONF")"
  assert_conf() { # label fixed-string
    case "$conf" in
      *"$2"*) pass "$1" ;;
      *) fail "$1 (missing: $2)" ;;
    esac
  }
  assert_conf "port 80 redirects to https with 301" "return 301 https://"
  assert_conf "https listener enables ssl + http2" "listen 443 ssl http2;"
  assert_conf "ssl_certificate points at the mounted fullchain" "ssl_certificate /etc/nginx/certs/fullchain.pem;"
  assert_conf "ssl_certificate_key points at the mounted privkey" "ssl_certificate_key /etc/nginx/certs/privkey.pem;"
  assert_conf "healthz endpoint exists" "location = /healthz"
  assert_conf "api requests proxy to backend:8080" "proxy_pass http://backend:8080/api/;"
  assert_conf "everything else proxies to frontend:80" "proxy_pass http://frontend:80;"
  assert_conf "X-Forwarded-Proto is set" "X-Forwarded-Proto \$scheme"
  assert_conf "X-Forwarded-For is set" "X-Forwarded-For \$proxy_add_x_forwarded_for"
  assert_conf "X-Real-IP is set" "X-Real-IP \$remote_addr"
  assert_conf "upload limit is 20m" "client_max_body_size 20m;"
  assert_conf "proxy read timeout is 120s" "proxy_read_timeout 120s;"
  assert_conf "X-Content-Type-Options is set" "X-Content-Type-Options nosniff"
  assert_conf "X-Frame-Options is SAMEORIGIN" "X-Frame-Options SAMEORIGIN"
  assert_conf "Referrer-Policy is set" "Referrer-Policy"
fi

if [ -d "$ROOT_DIR/deploy/certs" ]; then
  pass "deploy/certs directory exists (TLS material mount point)"
else
  fail "deploy/certs directory exists (TLS material mount point)"
fi

if grep -q '^deploy/certs/\*\.pem$' "$ROOT_DIR/.gitignore" 2>/dev/null; then
  pass ".gitignore excludes deploy/certs/*.pem"
else
  fail ".gitignore excludes deploy/certs/*.pem"
fi

echo
echo "== 3. backup / restore scripts =="

for file in "$BACKUP_SH" "$RESTORE_SH"; do
  rel="${file#"$ROOT_DIR"/}"
  if [ -f "$file" ] && bash -n "$file"; then
    pass "bash -n $rel"
  else
    fail "bash -n $rel"
  fi
  if [ -f "$file" ] && [ -x "$file" ]; then
    pass "$rel is executable"
  else
    fail "$rel is executable"
  fi
done

if [ -f "$RESTORE_SH" ]; then
  if "$RESTORE_SH" --help >/dev/null 2>&1; then
    pass "restore.sh --help exits 0"
  else
    fail "restore.sh --help exits 0"
  fi

  if grep -q 'COMPOSE_FILE' "$BACKUP_SH"; then
    pass "backup.sh honours COMPOSE_FILE"
  else
    fail "backup.sh honours COMPOSE_FILE"
  fi
  if grep -q 'COMPOSE_FILE' "$RESTORE_SH"; then
    pass "restore.sh honours COMPOSE_FILE"
  else
    fail "restore.sh honours COMPOSE_FILE"
  fi

  # A failing docker stub records every invocation, so "marker absent" proves the
  # dry run and the missing-dump guard never reach Docker.
  TMP="$(mktemp -d)"
  STUB_BIN="$TMP/bin"
  MARKER="$TMP/docker-was-called"
  mkdir -p "$STUB_BIN"
  {
    printf '#!/bin/sh\n'
    printf ': > "%s"\n' "$MARKER"
    printf 'exit 1\n'
  } > "$STUB_BIN/docker"
  chmod +x "$STUB_BIN/docker"

  out="$(cd "$ROOT_DIR" && env PATH="$STUB_BIN:$PATH" "$RESTORE_SH" 2>&1)"
  rc=$?
  if [ "$rc" -eq 2 ]; then
    pass "restore.sh without --yes exits 2"
  else
    fail "restore.sh without --yes exits 2 (got $rc)"
  fi
  case "$out" in
    *"--yes"*) pass "restore.sh dry run explains the --yes requirement" ;;
    *) fail "restore.sh dry run explains the --yes requirement: $out" ;;
  esac
  if [ -e "$MARKER" ]; then
    fail "restore.sh dry run never invokes docker"
    rm -f "$MARKER"
  else
    pass "restore.sh dry run never invokes docker"
  fi

  out="$(cd "$ROOT_DIR" && env PATH="$STUB_BIN:$PATH" "$RESTORE_SH" --yes "$TMP/does-not-exist.dump" 2>&1)"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    pass "restore.sh refuses a missing dump with a nonzero exit"
  else
    fail "restore.sh refuses a missing dump with a nonzero exit"
  fi
  if [ -e "$MARKER" ]; then
    fail "restore.sh validates the dump before touching docker"
    rm -f "$MARKER"
  else
    pass "restore.sh validates the dump before touching docker"
  fi

  rm -rf "$TMP"
fi

echo
echo "== 4. chore/prod-hardening ancestry =="

if (cd "$ROOT_DIR" && git merge-base --is-ancestor chore/prod-hardening HEAD) 2>/dev/null; then
  pass "chore/prod-hardening is an ancestor of HEAD (already merged)"
else
  fail "chore/prod-hardening is an ancestor of HEAD (already merged)"
fi

echo
echo "== 5. compose schema (optional) =="

if command -v docker >/dev/null 2>&1; then
  if (cd "$ROOT_DIR" && POSTGRES_PASSWORD="static-check-only" \
      docker compose -f docker-compose.prod.yml config -q); then
    pass "docker compose -f docker-compose.prod.yml config -q"
  else
    fail "docker compose -f docker-compose.prod.yml config -q"
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
