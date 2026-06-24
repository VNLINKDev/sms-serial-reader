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

if [ ! -s .previous-image ]; then
  echo "No .previous-image found. Cannot rollback automatically." >&2
  exit 1
fi

PREVIOUS_IMAGE="$(cat .previous-image)"
APP_IMAGE="${APP_IMAGE:-sms-serial-reader}"
APP_VERSION="${APP_VERSION:-rollback}"

docker tag "$PREVIOUS_IMAGE" "$APP_IMAGE:$APP_VERSION"
docker compose $COMPOSE_FILES -p "$PROJECT_NAME" up -d --no-build --remove-orphans sms-reader

echo "Waiting for container to rollback..."
sleep 5
STATE="$(docker compose $COMPOSE_FILES -p "$PROJECT_NAME" ps sms-reader --format "{{.State}}" 2>/dev/null || true)"
if [ "$STATE" = "running" ]; then
  echo "Rollback succeeded."
  exit 0
fi

echo "Rollback did not become running. Check container logs immediately." >&2
docker compose $COMPOSE_FILES -p "$PROJECT_NAME" logs --tail=200 sms-reader >&2
exit 1
