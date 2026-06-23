#!/bin/bash
# =============================================================================
# start.sh — Khởi động SMS Serial Reader
# =============================================================================

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$APP_DIR/app.pid"
LOG_DIR="$APP_DIR/logs"
LOG_FILE="$LOG_DIR/app.log"
ENV_FILE="$APP_DIR/.env"
JAR_FILE="$APP_DIR/app.jar"

# Auto-fix CRLF (Windows line endings) cho .env và các script
# Đảm bảo không bị lỗi "\r: command not found" khi upload từ Windows
for f in "$ENV_FILE" "$APP_DIR/start.sh" "$APP_DIR/stop.sh" "$APP_DIR/restart.sh"; do
    if [ -f "$f" ]; then
        sed -i 's/\r//' "$f"
    fi
done

# Màu sắc output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SMS Serial Reader — Start             ${NC}"
echo -e "${GREEN}========================================${NC}"

# --- Kiểm tra file JAR ---
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}[ERROR] Không tìm thấy file: $JAR_FILE${NC}"
    echo "        Hãy đảm bảo đã copy app.jar vào thư mục: $APP_DIR"
    exit 1
fi

# --- Kiểm tra file .env ---
if [ ! -f "$ENV_FILE" ]; then
    echo -e "${RED}[ERROR] Không tìm thấy file: $ENV_FILE${NC}"
    echo "        Hãy copy .env.example thành .env và điền giá trị thực."
    exit 1
fi

# --- Kiểm tra app đã chạy chưa ---
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if ps -p "$OLD_PID" > /dev/null 2>&1; then
        echo -e "${YELLOW}[WARN] App đang chạy với PID: $OLD_PID${NC}"
        echo "       Dùng './restart.sh' để khởi động lại."
        exit 1
    else
        echo -e "${YELLOW}[WARN] PID file cũ tồn tại nhưng process không chạy. Xóa PID cũ...${NC}"
        rm -f "$PID_FILE"
    fi
fi

# --- Tạo thư mục logs ---
mkdir -p "$LOG_DIR"

# --- Load biến từ .env ---
set -o allexport
source "$ENV_FILE"
set +o allexport

# Lấy SERVER_PORT từ env, mặc định 8080
SERVER_PORT="${SERVER_PORT:-8080}"
JAVA_OPTS="${JAVA_OPTS:--XX:MaxRAMPercentage=50 -XX:+UseSerialGC -Xss256k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError}"

echo -e "  JAR     : ${JAR_FILE}"
echo -e "  ENV     : ${ENV_FILE}"
echo -e "  LOG     : ${LOG_FILE}"
echo -e "  PORT    : ${SERVER_PORT}"
echo -e "  SERIAL  : ${SERIAL_DEVICE:-/dev/ttyUSB0}"
echo ""

# --- Khởi động app ---
nohup java $JAVA_OPTS -jar "$JAR_FILE" >> "$LOG_FILE" 2>&1 &
APP_PID=$!
echo $APP_PID > "$PID_FILE"

echo -e "${GREEN}[OK] App đã khởi động với PID: $APP_PID${NC}"
echo -e "     Log: tail -f $LOG_FILE"
echo ""

# --- Chờ health check (tối đa 60 giây) ---
echo -n "     Chờ app sẵn sàng"
for i in $(seq 1 30); do
    sleep 2
    if curl -sf "http://127.0.0.1:${SERVER_PORT}/actuator/health" | grep -q '"status":"UP"'; then
        echo ""
        echo -e "${GREEN}[OK] App UP — http://127.0.0.1:${SERVER_PORT}/actuator/health${NC}"
        exit 0
    fi
    # Kiểm tra process còn sống không
    if ! ps -p "$APP_PID" > /dev/null 2>&1; then
        echo ""
        echo -e "${RED}[ERROR] Process đã dừng bất thường! Xem log:${NC}"
        echo "        tail -50 $LOG_FILE"
        rm -f "$PID_FILE"
        exit 1
    fi
    echo -n "."
done

echo ""
echo -e "${YELLOW}[WARN] App chưa báo UP sau 60s. Kiểm tra log:${NC}"
echo "       tail -f $LOG_FILE"