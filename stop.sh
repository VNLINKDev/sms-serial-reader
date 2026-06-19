#!/bin/bash
# =============================================================================
# stop.sh — Dừng SMS Serial Reader
# =============================================================================

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$APP_DIR/app.pid"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SMS Serial Reader — Stop              ${NC}"
echo -e "${GREEN}========================================${NC}"

if [ ! -f "$PID_FILE" ]; then
    echo -e "${YELLOW}[WARN] Không tìm thấy PID file. App chưa chạy hoặc đã dừng.${NC}"
    exit 0
fi

APP_PID=$(cat "$PID_FILE")

if ! ps -p "$APP_PID" > /dev/null 2>&1; then
    echo -e "${YELLOW}[WARN] Process PID $APP_PID không còn chạy. Xóa PID file...${NC}"
    rm -f "$PID_FILE"
    exit 0
fi

echo -e "  Đang dừng PID: ${APP_PID} ..."
kill -TERM "$APP_PID"

# Chờ process tắt graceful (tối đa 15 giây)
for i in $(seq 1 15); do
    if ! ps -p "$APP_PID" > /dev/null 2>&1; then
        rm -f "$PID_FILE"
        echo -e "${GREEN}[OK] App đã dừng gracefully.${NC}"
        exit 0
    fi
    sleep 1
    echo -n "."
done

echo ""
echo -e "${YELLOW}[WARN] App chưa dừng sau 15s, gửi SIGKILL...${NC}"
kill -KILL "$APP_PID" 2>/dev/null
sleep 1

rm -f "$PID_FILE"

if ps -p "$APP_PID" > /dev/null 2>&1; then
    echo -e "${RED}[ERROR] Không thể dừng process PID: $APP_PID${NC}"
    exit 1
else
    echo -e "${GREEN}[OK] App đã bị kill.${NC}"
fi
