#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-sms-reader}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
REDIS_CONTAINER="${REDIS_CONTAINER:-sms-reader-redis}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_FILE="$BACKUP_DIR/redis-$STAMP.rdb"

mkdir -p "$BACKUP_DIR"

if [ -n "${REDIS_PASSWORD:-}" ]; then
  docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" SAVE >/dev/null
else
  docker exec "$REDIS_CONTAINER" redis-cli SAVE >/dev/null
fi

docker cp "$REDIS_CONTAINER:/data/dump.rdb" "$BACKUP_FILE"
sha256sum "$BACKUP_FILE" > "$BACKUP_FILE.sha256"

echo "Redis backup created: $BACKUP_FILE"
echo "Project: $PROJECT_NAME"
