#!/usr/bin/env bash
set -Eeuo pipefail

ENVIRONMENT="${1:-staging}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-sms-reader}"

case "$ENVIRONMENT" in
  local|dev)
    COMPOSE_FILES="-f docker-compose.yml"
    ;;
  staging)
    COMPOSE_FILES="-f docker-compose.yml -f docker-compose.staging.yml"
    ;;
  prod|production)
    COMPOSE_FILES="-f docker-compose.yml -f docker-compose.prod.yml"
    ;;
  *)
    echo "Unsupported environment: $ENVIRONMENT" >&2
    exit 2
    ;;
esac

if [ ! -f .env ]; then
  echo "Missing .env. Create it from .env.example and fill production values." >&2
  exit 1
fi

mkdir -p logs backups

CURRENT_IMAGE="$(docker compose $COMPOSE_FILES -p "$PROJECT_NAME" images -q sms-reader 2>/dev/null | head -n1 || true)"
if [ -n "$CURRENT_IMAGE" ]; then
  echo "$CURRENT_IMAGE" > .previous-image
fi

docker compose $COMPOSE_FILES -p "$PROJECT_NAME" pull --ignore-pull-failures
docker compose $COMPOSE_FILES -p "$PROJECT_NAME" build sms-reader
docker compose $COMPOSE_FILES -p "$PROJECT_NAME" up -d --remove-orphans

echo "Waiting for container to start..."
sleep 5

STATE="$(docker compose $COMPOSE_FILES -p "$PROJECT_NAME" ps sms-reader --format "{{.State}}" 2>/dev/null || true)"
if [ "$STATE" = "running" ]; then
  echo "Deploy succeeded."
  docker compose $COMPOSE_FILES -p "$PROJECT_NAME" ps
  exit 0
fi

echo "Deploy failed: sms-reader container is not running (State: ${STATE:-unknown})." >&2
docker compose $COMPOSE_FILES -p "$PROJECT_NAME" logs --tail=200 sms-reader >&2
./scripts/rollback.sh "$ENVIRONMENT" || true
exit 1
