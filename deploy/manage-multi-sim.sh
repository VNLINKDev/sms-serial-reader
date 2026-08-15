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
  info      Hiển thị số SIM, port host và số nhận SMS keep-alive
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
  ./manage-multi-sim.sh info all
  ./manage-multi-sim.sh logs sim02
  ./manage-multi-sim.sh status
EOF
}

fail() {
    echo "[ERROR] $*" >&2
    exit 1
}

read_env_value() {
    local env_file="$1"
    local wanted_key="$2"
    local key value

    while IFS='=' read -r key value; do
        if [ "$key" = "$wanted_key" ]; then
            value="${value%$'\r'}"
            if [[ "$value" =~ ^\".*\"$ ]] || [[ "$value" =~ ^\'.*\'$ ]]; then
                value="${value:1:${#value}-2}"
            fi
            echo "$value"
            return 0
        fi
    done < "$env_file"

    echo "(chưa cấu hình)"
}

show_service_info() {
    local service="$1"
    local env_file host_device

    case "$service" in
        sim01)
            env_file="$SCRIPT_DIR/env/.envsim84832019510"
            host_device="/dev/ttyUSB0"
            ;;
        sim02)
            env_file="$SCRIPT_DIR/env/.envsim84812943652"
            host_device="/dev/ttyUSB3"
            ;;
    esac

    [ -f "$env_file" ] || fail "Không tìm thấy file env của $service: $env_file"

    echo "Service: $service"
    echo "  PHONE_NUMBER: $(read_env_value "$env_file" PHONE_NUMBER)"
    echo "  Port trên host: $host_device"
    echo "  Port trong container: /dev/modem"
    echo "  KEEP_ALIVE_PHONE_NUMBER: $(read_env_value "$env_file" KEEP_ALIVE_PHONE_NUMBER)"
}

command_name="${1:-help}"
target="${2:-all}"

case "$command_name" in
    help|-h|--help) usage; exit 0 ;;
    start|stop|restart|status|info|logs) ;;
    *) usage >&2; fail "Lệnh không hợp lệ: $command_name" ;;
esac

case "$target" in
    sim01|sim02) services=("$target") ;;
    all) services=(sim01 sim02) ;;
    *) fail "SIM không hợp lệ: $target. Chỉ chấp nhận sim01, sim02 hoặc all." ;;
esac

[ "$#" -le 2 ] || fail "Có tham số dư. Dùng --help để xem hướng dẫn."

if [ "$command_name" = "info" ]; then
    for service in "${services[@]}"; do
        show_service_info "$service"
    done
    exit 0
fi

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
