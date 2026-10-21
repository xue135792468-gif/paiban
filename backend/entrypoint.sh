#!/bin/sh
# Start as root only long enough to make the bind-mounted uploads directory
# writable by the unprivileged runtime user, then drop privileges.
set -e

mkdir -p /app/uploads /app/data
chown -R paiban:paiban /app/uploads /app/data

exec gosu paiban java ${JAVA_OPTS:-} -jar /app/app.jar
