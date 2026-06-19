#!/bin/bash
# =============================================================================
# restart.sh — Khởi động lại SMS Serial Reader
# =============================================================================

APP_DIR="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SMS Serial Reader — Restart           ${NC}"
echo -e "${GREEN}========================================${NC}"

bash "$APP_DIR/stop.sh"

echo ""
echo "  Chờ 3 giây trước khi khởi động lại..."
sleep 3
echo ""

bash "$APP_DIR/start.sh"
