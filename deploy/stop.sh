#!/bin/bash
# =============================================================================
# stop.sh — Dừng một SMS reader container theo SIM
# Cách dùng: ./stop.sh sim01 | ./stop.sh sim02
# =============================================================================

set -u

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$APP_DIR/docker-compose.yml"
SIM_NAME="${1:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

case "$SIM_NAME" in
    sim01|sim02)
        SERVICE_NAME="$SIM_NAME"
        ;;
    *)
        echo -e "${RED}[ERROR] SIM không hợp lệ: '${SIM_NAME}'.${NC}"
        echo "Cách dùng: $0 sim01 | $0 sim02"
        exit 1
        ;;
esac

if ! command -v docker >/dev/null 2>&1; then
    echo -e "${RED}[ERROR] Không tìm thấy lệnh docker.${NC}"
    exit 1
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SMS Serial Reader — Stop ${SIM_NAME}        ${NC}"
echo -e "${GREEN}========================================${NC}"

docker compose -f "$COMPOSE_FILE" stop "$SERVICE_NAME"
EXIT_CODE=$?

if [ "$EXIT_CODE" -ne 0 ]; then
    echo -e "${RED}[ERROR] Không thể dừng service '${SERVICE_NAME}'.${NC}"
    exit "$EXIT_CODE"
fi

echo -e "${GREEN}[OK] Service '${SERVICE_NAME}' đã dừng.${NC}"
