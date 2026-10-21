#!/usr/bin/env bash
# Back up PostgreSQL and uploaded files.
#
# Usage:
#   ./scripts/backup.sh [--compose-file FILE]
#
# Environment:
#   COMPOSE_FILE    compose file to use (same as --compose-file)
#   BACKUP_DIR      output directory (default <repo>/backups)
#   RETENTION_DAYS  delete backups older than N days (default 14)
#   POSTGRES_DB     database name (default paiban)
#   POSTGRES_USER   database user (default paiban)
#
# Restore with scripts/restore.sh (dry run by default):
#   ./scripts/restore.sh
#   COMPOSE_FILE=docker-compose.prod.yml ./scripts/restore.sh --yes
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/compose.sh
. "$ROOT/scripts/lib/compose.sh"

COMPOSE_FILE="${COMPOSE_FILE:-}"
BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
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

Environment: COMPOSE_FILE, BACKUP_DIR (default <repo>/backups),
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

mkdir -p "$BACKUP_DIR"

# Dump to a temp file and only publish the final name once pg_dump succeeded,
# so a failed dump never leaves an empty/truncated paiban-*.dump behind.
tmp="$(mktemp "$BACKUP_DIR/.paiban-$STAMP.XXXXXX")"
if ! paiban_compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$tmp"; then
  rm -f "$tmp"
  printf 'ERROR: pg_dump failed; no backup was written\n' >&2
  exit 1
fi
if [[ ! -s "$tmp" ]]; then
  rm -f "$tmp"
  printf 'ERROR: pg_dump produced an empty file; no backup was written\n' >&2
  exit 1
fi
mv "$tmp" "$BACKUP_DIR/paiban-$STAMP.dump"

if [[ -d "$ROOT/data/uploads" ]]; then
  tar -czf "$BACKUP_DIR/uploads-$STAMP.tar.gz" -C "$ROOT/data" uploads
fi

# Retention only touches this script's own artefacts, never other files in the
# directory (the previous glob deleted everything older than N days).
find "$BACKUP_DIR" -type f -name 'paiban-*.dump' -mtime "+$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -type f -name 'uploads-*.tar.gz' -mtime "+$RETENTION_DAYS" -delete
echo "Backup written to $BACKUP_DIR (retention: ${RETENTION_DAYS} days)"
