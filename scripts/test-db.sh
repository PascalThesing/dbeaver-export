#!/usr/bin/env bash
# Starts/stops the PostgreSQL container used by the integration/perf tests.
# Usage: scripts/test-db.sh start|stop|env
set -euo pipefail

NAME=exportnow-pg
IMAGE=docker.io/library/postgres:16
PORT=15432
PASSWORD=exportnow

engine() {
    if command -v podman >/dev/null 2>&1; then echo podman; else echo docker; fi
}

case "${1:-}" in
    start)
        E=$(engine)
        $E rm -f $NAME >/dev/null 2>&1 || true
        $E run -d --name $NAME -e POSTGRES_PASSWORD=$PASSWORD -p $PORT:5432 $IMAGE >/dev/null
        echo "waiting for postgres..."
        for i in $(seq 1 60); do
            $E exec $NAME pg_isready -U postgres >/dev/null 2>&1 && break
            sleep 1
        done
        $E exec $NAME pg_isready -U postgres
        echo "export EXPORTNOW_PG_URL=jdbc:postgresql://localhost:$PORT/postgres"
        echo "export EXPORTNOW_PG_USER=postgres"
        echo "export EXPORTNOW_PG_PASSWORD=$PASSWORD"
        ;;
    stop)
        $(engine) rm -f $NAME
        ;;
    env)
        echo "export EXPORTNOW_PG_URL=jdbc:postgresql://localhost:$PORT/postgres"
        echo "export EXPORTNOW_PG_USER=postgres"
        echo "export EXPORTNOW_PG_PASSWORD=$PASSWORD"
        ;;
    *)
        echo "usage: $0 start|stop|env" >&2
        exit 1
        ;;
esac
