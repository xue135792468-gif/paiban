#!/usr/bin/env bash
# Restore a PostgreSQL dump (and the matching uploads archive) into a compose stack.
#
# Usage:
#   ./scripts/restore.sh [--yes] [--compose-file FILE] [DUMP_FILE]
#
#   --yes                  actually perform the restore; without it the script
#                          only prints the plan and exits 2 (never prompts)
#   --compose-file FILE    compose file to use (default: docker-compose.yml)
#   DUMP_FILE              dump to restore (default: newest backups/paiban-*.dump)
#
# Environment:
#   COMPOSE_FILE           same as --compose-file (flag wins)
#   BACKUP_DIR             where dumps live (default ./backups)
#   DATA_DIR               uploads root (default ./data)
#   POSTGRES_DB            database name (default paiban)
#   POSTGRES_USER          database user (default paiban)
#
# Restore procedure: stop backend and frontend, pg_restore --clean --if-exists
# into the postgres service, unpack backups/uploads-<stamp>.tar.gz when present,
# then start backend and frontend again.
#
# Exit codes: 0 = restored; 1 = restore failed; 2 = dry run or usage error.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}"
DATA_DIR="${DATA_DIR:-$ROOT_DIR/data}"
DB_NAME="${POSTGRES_DB:-paiban}"
DB_USER="${POSTGRES_USER:-paiban}"
COMPOSE_FILE="${COMPOSE_FILE:-}"

ASSUME_YES=0
DUMP=""

usage() {
  cat <<'EOF'
Usage: ./scripts/restore.sh [--yes] [--compose-file FILE] [DUMP_FILE]

Restore PostgreSQL (and uploads) from a backup made by scripts/backup.sh.

  --yes                  perform the restore; without it only print the plan (exit 2)
  --compose-file FILE    compose file to use (default: docker-compose.yml)
  DUMP_FILE              dump to restore (default: newest backups/paiban-*.dump)
  -h, --help             show this help

Environment: COMPOSE_FILE, BACKUP_DIR, DATA_DIR, POSTGRES_DB, POSTGRES_USER.

Examples:
  ./scripts/restore.sh                                  # dry run, prints the plan
  ./scripts/restore.sh --yes                            # restore the newest dump
  COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh --yes backups/paiban-20261021-120000.dump

Exit codes: 0 = restored; 1 = restore failed; 2 = dry run or usage error.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes|-y)
      ASSUME_YES=1
      shift
      ;;
    --compose-file)
      if [[ $# -lt 2 || -z "$2" ]]; then
        printf 'ERROR: --compose-file needs a path\n' >&2
        exit 2
      fi
      COMPOSE_FILE="$2"
      shift 2
      ;;
    --compose-file=*)
      COMPOSE_FILE="${1#*=}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      printf 'ERROR: unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$DUMP" ]]; then
        printf 'ERROR: only one dump file may be given\n' >&2
        exit 2
      fi
      DUMP="$1"
      shift
      ;;
  esac
done

compose() {
  if [[ -n "$COMPOSE_FILE" ]]; then
    docker compose -f "$COMPOSE_FILE" "$@"
  else
    docker compose "$@"
  fi
}

if [[ -z "$DUMP" ]]; then
  DUMP="$(ls -1t "$BACKUP_DIR"/paiban-*.dump 2>/dev/null | head -n 1 || true)"
fi

STAMP=""
if [[ -n "$DUMP" ]]; then
  base="$(basename "$DUMP")"
  STAMP="${base#paiban-}"
  STAMP="${STAMP%.dump}"
fi

UPLOADS=""
if [[ -n "$STAMP" ]]; then
  UPLOADS="$BACKUP_DIR/uploads-$STAMP.tar.gz"
fi

compose_label="${COMPOSE_FILE:-docker-compose.yml (default)}"

if [[ "$ASSUME_YES" -ne 1 ]]; then
  echo "Restore plan (dry run - nothing was changed):"
  echo "  compose file : $compose_label"
  echo "  database     : $DB_NAME (user $DB_USER)"
  if [[ -n "$DUMP" ]]; then
    echo "  dump         : $DUMP"
  else
    echo "  dump         : none found in $BACKUP_DIR (pass DUMP_FILE explicitly)"
  fi
  if [[ -n "$UPLOADS" && -f "$UPLOADS" ]]; then
    echo "  uploads      : $UPLOADS"
  else
    echo "  uploads      : none (database only)"
  fi
  echo "Steps:"
  echo "  1. docker compose ... stop backend frontend"
  echo "  2. docker compose ... exec -T postgres pg_restore -U $DB_USER -d $DB_NAME --clean --if-exists"
  echo "  3. unpack uploads archive into $DATA_DIR (when present)"
  echo "  4. docker compose ... up -d backend frontend"
  echo
  echo "Nothing was changed. Re-run with --yes to execute this plan."
  exit 2
fi

if [[ -z "$DUMP" || ! -f "$DUMP" ]]; then
  printf 'ERROR: dump file not found: %s\n' "${DUMP:-<none>}" >&2
  exit 1
fi
if [[ ! -s "$DUMP" ]]; then
  printf 'ERROR: dump file is empty: %s\n' "$DUMP" >&2
  exit 1
fi

step() { printf '==> %s\n' "$*"; }
on_error() { printf 'ERROR: restore failed at the previous step\n' >&2; }
trap on_error ERR

step "Using compose file: $compose_label"
step "Restoring dump: $DUMP"

step "Stopping backend and frontend"
compose stop backend frontend

step "Restoring database $DB_NAME (pg_restore --clean --if-exists)"
compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --clean --if-exists < "$DUMP"

if [[ -n "$UPLOADS" && -f "$UPLOADS" ]]; then
  step "Restoring uploads from $UPLOADS"
  mkdir -p "$DATA_DIR"
  tar -xzf "$UPLOADS" -C "$DATA_DIR"
else
  step "No uploads archive for this dump, skipping file restore"
fi

step "Starting backend and frontend"
compose up -d backend frontend

step "Restore complete. Check health with: docker compose ... ps"
