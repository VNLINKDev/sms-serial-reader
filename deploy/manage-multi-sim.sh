#!/usr/bin/env bash

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

usage() {
    cat <<'EOF'
Quản lý nhiều modem/SIM cắm trên cùng một host.

Cách dùng:
  ./manage-multi-sim.sh <lệnh> [sim01|sim02|all]

Lệnh:
  start     Build image và khởi động service
  stop      Dừng service, không xóa container
  restart   Dừng, build lại và khởi động service
  status    Hiển thị trạng thái container
  logs      Theo dõi log; nhấn Ctrl+C để thoát
  help      Hiển thị trợ giúp

Đối tượng:
  sim01     Chỉ thao tác service sim01
  sim02     Chỉ thao tác service sim02
  all       Thao tác cả hai service (mặc định)

Ví dụ:
  ./manage-multi-sim.sh start all
  ./manage-multi-sim.sh restart sim01
  ./manage-multi-sim.sh stop sim01
  ./manage-multi-sim.sh stop all
  ./manage-multi-sim.sh logs sim02
  ./manage-multi-sim.sh status
EOF
}

fail() {
    echo "[ERROR] $*" >&2
    exit 1
}

command_name="${1:-help}"
target="${2:-all}"

case "$command_name" in
    help|-h|--help) usage; exit 0 ;;
    start|stop|restart|status|logs) ;;
    *) usage >&2; fail "Lệnh không hợp lệ: $command_name" ;;
esac

case "$target" in
    sim01|sim02) services=("$target") ;;
    all) services=(sim01 sim02) ;;
    *) fail "SIM không hợp lệ: $target. Chỉ chấp nhận sim01, sim02 hoặc all." ;;
esac

[ "$#" -le 2 ] || fail "Có tham số dư. Dùng --help để xem hướng dẫn."
command -v docker >/dev/null 2>&1 || fail "Không tìm thấy lệnh docker."
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin chưa sẵn sàng."

compose=(docker compose -f "$COMPOSE_FILE")

case "$command_name" in
    start) "${compose[@]}" up -d --build "${services[@]}" ;;
    stop) "${compose[@]}" stop "${services[@]}" ;;
    restart)
        "${compose[@]}" stop "${services[@]}" &&
            "${compose[@]}" up -d --build "${services[@]}"
        ;;
    status) "${compose[@]}" ps "${services[@]}" ;;
    logs) "${compose[@]}" logs -f "${services[@]}" ;;
esac
