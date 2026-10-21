#!/usr/bin/env bash
# Back up PostgreSQL and uploaded files.
# Run from the repository root while the compose stack is running:
#   ./scripts/backup.sh
#
# Restore:
#   docker compose exec -T postgres pg_restore -U "${POSTGRES_USER:-paiban}" \
#     -d "${POSTGRES_DB:-paiban}" --clean --if-exists < backups/paiban-<stamp>.dump
#   tar -xzf backups/uploads-<stamp>.tar.gz -C data
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DB_NAME="${POSTGRES_DB:-paiban}"
DB_USER="${POSTGRES_USER:-paiban}"

mkdir -p "$BACKUP_DIR"

docker compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc \
  > "$BACKUP_DIR/paiban-$STAMP.dump"

if [[ -d data/uploads ]]; then
  tar -czf "$BACKUP_DIR/uploads-$STAMP.tar.gz" -C data uploads
fi

find "$BACKUP_DIR" -type f -mtime "+$RETENTION_DAYS" -delete
echo "Backup written to $BACKUP_DIR (retention: ${RETENTION_DAYS} days)"
