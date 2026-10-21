#!/usr/bin/env bash
# Back up PostgreSQL and uploaded files.
#
# Usage:
#   ./scripts/backup.sh [--compose-file FILE]
#
# Environment:
#   COMPOSE_FILE    compose file to use (same as --compose-file)
#   BACKUP_DIR      output directory (default ./backups)
#   RETENTION_DAYS  delete backups older than N days (default 14)
#   POSTGRES_DB     database name (default paiban)
#   POSTGRES_USER   database user (default paiban)
#
# Restore with scripts/restore.sh (dry run by default):
#   ./scripts/restore.sh
#   COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh --yes
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DB_NAME="${POSTGRES_DB:-paiban}"
DB_USER="${POSTGRES_USER:-paiban}"

usage() {
  cat <<'EOF'
Usage: ./scripts/backup.sh [--compose-file FILE]

Write a PostgreSQL dump and an uploads archive into BACKUP_DIR.

  --compose-file FILE    compose file to use (default: docker-compose.yml)
  -h, --help             show this help

Environment: COMPOSE_FILE, BACKUP_DIR (default ./backups),
RETENTION_DAYS (default 14), POSTGRES_DB, POSTGRES_USER.

Examples:
  ./scripts/backup.sh
  COMPOSE_FILE=docker-compose.prod.yml ./scripts/backup.sh
  ./scripts/backup.sh --compose-file docker-compose.prod.yml
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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
    *)
      printf 'ERROR: unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
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

mkdir -p "$BACKUP_DIR"

compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc \
  > "$BACKUP_DIR/paiban-$STAMP.dump"

if [[ -d data/uploads ]]; then
  tar -czf "$BACKUP_DIR/uploads-$STAMP.tar.gz" -C data uploads
fi

find "$BACKUP_DIR" -type f -mtime "+$RETENTION_DAYS" -delete
echo "Backup written to $BACKUP_DIR (retention: ${RETENTION_DAYS} days)"
