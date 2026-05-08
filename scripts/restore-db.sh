#!/usr/bin/env bash
set -Eeuo pipefail

BACKUP_FILE="${1:-}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-sms-reader}"
REDIS_CONTAINER="${REDIS_CONTAINER:-sms-reader-redis}"

if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "Usage: ./scripts/restore-db.sh ./backups/redis-YYYYMMDD-HHMMSS.rdb" >&2
  exit 2
fi

if [ "${CONFIRM_RESTORE:-}" != "yes" ]; then
  echo "Refusing restore without CONFIRM_RESTORE=yes." >&2
  echo "Restore overwrites Redis data for container $REDIS_CONTAINER." >&2
  exit 1
fi

docker compose -p "$PROJECT_NAME" stop sms-reader
docker stop "$REDIS_CONTAINER"
docker cp "$BACKUP_FILE" "$REDIS_CONTAINER:/data/dump.rdb"
docker start "$REDIS_CONTAINER"

if [ -n "${REDIS_PASSWORD:-}" ]; then
  docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" ping
else
  docker exec "$REDIS_CONTAINER" redis-cli ping
fi

docker compose -p "$PROJECT_NAME" start sms-reader
echo "Redis restore completed from: $BACKUP_FILE"
