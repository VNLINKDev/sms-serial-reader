#!/bin/bash
# =============================================================================
# start.sh — Khởi động SMS Serial Reader
# =============================================================================

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$APP_DIR/app.pid"
LOG_DIR="$APP_DIR/logs"
BOOT_LOG_FILE="$LOG_DIR/app.log"
ENV_FILE="$APP_DIR/.env"
JAR_FILE="$APP_DIR/sms-serial-reader.jar"

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
    echo "        Hãy đảm bảo đã copy sms-serial-reader.jar vào thư mục: $APP_DIR"
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
APP_LOG_DIR="${LOG_PATH:-$LOG_DIR}"
APP_LOG_FILE="$APP_LOG_DIR/sms-reader.log"
mkdir -p "$APP_LOG_DIR" 2>/dev/null || true

if [ "$BOOT_LOG_FILE" = "$APP_LOG_FILE" ]; then
    BOOT_LOG_FILE="$LOG_DIR/app.log"
fi

echo -e "  JAR     : ${JAR_FILE}"
echo -e "  ENV     : ${ENV_FILE}"
echo -e "  BOOTLOG : ${BOOT_LOG_FILE}"
echo -e "  APPLOG  : ${APP_LOG_FILE}"
echo -e "  PORT    : ${SERVER_PORT}"
echo -e "  SERIAL  : ${SERIAL_DEVICE:-/dev/ttyUSB0}"
echo ""

# --- Khởi động app ---
nohup java $JAVA_OPTS -jar "$JAR_FILE" >> "$BOOT_LOG_FILE" 2>&1 &
APP_PID=$!
echo $APP_PID > "$PID_FILE"

echo -e "${GREEN}[OK] App đã khởi động với PID: $APP_PID${NC}"
echo -e "     Log khởi động : tail -f $BOOT_LOG_FILE"
echo -e "     Log ứng dụng  : tail -f $APP_LOG_FILE"
echo ""

# --- Kiểm tra tiến trình khởi chạy (chờ 5 giây xem process có sống không) ---
echo -n "     Kiểm tra tiến trình khởi chạy"
for i in $(seq 1 5); do
    sleep 1
    if ! ps -p "$APP_PID" > /dev/null 2>&1; then
        echo ""
        echo -e "${RED}[ERROR] Tiến trình đã dừng bất thường! Xem log:${NC}"
        echo "        tail -50 $BOOT_LOG_FILE"
        tail -50 "$BOOT_LOG_FILE" 2>/dev/null || true
        if [ -f "$APP_LOG_FILE" ]; then
            echo ""
            echo "        tail -50 $APP_LOG_FILE"
            tail -50 "$APP_LOG_FILE" 2>/dev/null || true
        fi
        rm -f "$PID_FILE"
        exit 1
    fi
    echo -n "."
done
echo ""
echo -e "${GREEN}[OK] Tiến trình khởi chạy thành công (PID: $APP_PID)${NC}"
echo ""
echo "----- 20 dòng cuối log khởi động ($BOOT_LOG_FILE) -----"
tail -20 "$BOOT_LOG_FILE" 2>/dev/null || true
if [ -f "$APP_LOG_FILE" ]; then
    echo ""
    echo "----- 20 dòng cuối log ứng dụng ($APP_LOG_FILE) -----"
    tail -20 "$APP_LOG_FILE" 2>/dev/null || true
else
    echo ""
    echo -e "${YELLOW}[WARN] Chưa thấy file log ứng dụng: $APP_LOG_FILE${NC}"
    echo "       Nếu LOG_PATH trong .env trỏ tới thư mục khác hoặc không có quyền ghi, hãy kiểm tra lại LOG_PATH."
fi
exit 0
