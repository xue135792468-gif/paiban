#!/bin/sh
# Start as root only long enough to make the bind-mounted uploads directory
# writable by the unprivileged runtime user, then drop privileges.
set -e

mkdir -p /app/uploads /app/data
chown -R paiban:paiban /app/uploads /app/data

# Self-healing secrets (ticket 03): reuse /app/data/secrets.env or generate the
# missing ones, then export them so the java process inherits the values.
# Sourced on purpose - a plain exec would lose the exported variables.
. /app/secret-bootstrap.sh
if [ -f /app/data/secrets.env ]; then
  chown paiban:paiban /app/data/secrets.env 2>/dev/null || true
fi

exec gosu paiban java ${JAVA_OPTS:-} -jar /app/app.jar
