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

docker compose $COMPOSE_FILES -p "$PROJECT_NAME" restart sms-reader
docker compose $COMPOSE_FILES -p "$PROJECT_NAME" ps
