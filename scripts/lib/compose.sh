#!/usr/bin/env bash
# Shared compose invocation for the maintenance scripts (backup / restore).
#
# COMPOSE_FILE selects the compose file (scripts also accept --compose-file,
# which sets COMPOSE_FILE before calling this). Empty means the default
# docker-compose.yml in the current directory.
#
# Usage:
#   . "$(dirname "${BASH_SOURCE[0]}")/lib/compose.sh"
#   paiban_compose exec -T postgres pg_dump -U paiban -d paiban -Fc

paiban_compose() {
  if [[ -n "${COMPOSE_FILE:-}" ]]; then
    docker compose -f "$COMPOSE_FILE" "$@"
  else
    docker compose "$@"
  fi
}
