#!/bin/bash
# =============================================================================
# restart.sh — Khởi động lại một SMS reader container theo SIM
# Cách dùng: ./restart.sh sim01 | ./restart.sh sim02
# =============================================================================

set -u

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SIM_NAME="${1:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

case "$SIM_NAME" in
    sim01|sim02)
        ;;
    *)
        echo -e "${RED}[ERROR] SIM không hợp lệ: '${SIM_NAME}'.${NC}"
        echo "Cách dùng: $0 sim01 | $0 sim02"
        exit 1
        ;;
esac

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SMS Serial Reader — Restart ${SIM_NAME}     ${NC}"
echo -e "${GREEN}========================================${NC}"

bash "$APP_DIR/stop.sh" "$SIM_NAME"
STOP_CODE=$?
if [ "$STOP_CODE" -ne 0 ]; then
    echo -e "${RED}[ERROR] Không thể dừng '${SIM_NAME}'.${NC}"
    exit "$STOP_CODE"
fi

bash "$APP_DIR/start.sh" "$SIM_NAME"
START_CODE=$?
if [ "$START_CODE" -ne 0 ]; then
    echo -e "${RED}[ERROR] Không thể khởi động lại '${SIM_NAME}'.${NC}"
    exit "$START_CODE"
fi

echo -e "${GREEN}[OK] Restart '${SIM_NAME}' hoàn tất.${NC}"
