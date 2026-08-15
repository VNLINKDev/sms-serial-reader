#!/usr/bin/env bash

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

usage() {
    cat <<'EOF'
Quản lý nhiều modem/SIM cắm trên cùng một host.

Cách dùng:
  ./manage-multi-sim.sh [--engine auto|docker|podman] <lệnh> [sim01|sim02|all]

Lệnh:
  start     Build image và khởi động service
  stop      Dừng service, không xóa container
  restart   Dừng, build lại và khởi động service
  status    Hiển thị trạng thái container
  info      Hiển thị số SIM, port host và số nhận SMS keep-alive
  logs      Theo dõi log; nhấn Ctrl+C để thoát
  help      Hiển thị trợ giúp

Container engine:
  auto      Tự chọn Docker trước, sau đó Podman (mặc định)
  docker    Bắt buộc sử dụng Docker Compose
  podman    Bắt buộc sử dụng Podman Compose

Đối tượng:
  sim01     Chỉ thao tác service sim01
  sim02     Chỉ thao tác service sim02
  all       Thao tác cả hai service (mặc định)

Ví dụ:
  ./manage-multi-sim.sh start all
  ./manage-multi-sim.sh --engine podman start all
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

select_engine() {
    case "$engine_choice" in
        auto)
            if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
                engine="docker"
            elif command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
                engine="podman"
            else
                fail "Không tìm thấy Docker Compose hoặc Podman Compose khả dụng."
            fi
            ;;
        docker)
            command -v docker >/dev/null 2>&1 || fail "Không tìm thấy lệnh docker."
            docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin chưa sẵn sàng."
            engine="docker"
            ;;
        podman)
            command -v podman >/dev/null 2>&1 || fail "Không tìm thấy lệnh podman."
            podman compose version >/dev/null 2>&1 ||
                fail "Podman Compose chưa sẵn sàng. Hãy cài podman-compose hoặc docker-compose làm Compose provider."
            engine="podman"
            ;;
        *) fail "Container engine không hợp lệ: $engine_choice. Chỉ chấp nhận auto, docker hoặc podman." ;;
    esac
}

is_podman_rootless() {
    [ "$engine" = "podman" ] &&
        [ "$(podman info --format '{{.Host.Security.Rootless}}' 2>/dev/null)" = "true" ]
}

host_device_for_service() {
    case "$1" in
        sim01) echo "/dev/ttyUSB0" ;;
        sim02) echo "/dev/ttyUSB3" ;;
    esac
}

show_podman_permission_hint() {
    cat >&2 <<'EOF'
[HƯỚNG DẪN] Podman rootless có thể không được phép truy cập USB serial.
  - Kiểm tra: ls -l /dev/ttyUSB*
  - Thêm user vào group sở hữu device (thường là dialout), rồi đăng xuất/đăng nhập lại.
  - Nếu quyền chỉ đến từ supplementary group, Podman cần keep-groups.
  - Trên host dùng SELinux, có thể cần bật container_use_devices.
  - Nếu vẫn lỗi, chạy Podman rootful hoặc xem mục Podman rootless trong README.md.
EOF
}

check_podman_device_permissions() {
    local service host_device

    is_podman_rootless || return 0

    for service in "${services[@]}"; do
        host_device="$(host_device_for_service "$service")"
        [ -e "$host_device" ] || fail "Không tìm thấy USB serial của $service: $host_device"
        if [ ! -r "$host_device" ] || [ ! -w "$host_device" ]; then
            echo "[ERROR] Podman rootless không có quyền đọc/ghi $host_device cho $service." >&2
            show_podman_permission_hint
            return 1
        fi
    done
}

run_compose_with_podman_hint() {
    if ! "${compose[@]}" "$@"; then
        is_podman_rootless && show_podman_permission_hint
        return 1
    fi
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

engine_choice="auto"
positional=()

while [ "$#" -gt 0 ]; do
    case "$1" in
        --engine)
            [ "$#" -ge 2 ] || fail "Thiếu giá trị cho --engine."
            engine_choice="$2"
            shift 2
            ;;
        *) positional+=("$1"); shift ;;
    esac
done

set -- "${positional[@]}"
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

case "$engine_choice" in
    auto|docker|podman) ;;
    *) fail "Container engine không hợp lệ: $engine_choice. Chỉ chấp nhận auto, docker hoặc podman." ;;
esac

if [ "$command_name" = "info" ]; then
    for service in "${services[@]}"; do
        show_service_info "$service"
    done
    exit 0
fi

select_engine
compose=("$engine" compose -f "$COMPOSE_FILE")

case "$command_name" in
    start|restart) check_podman_device_permissions ;;
esac

case "$command_name" in
    start) run_compose_with_podman_hint up -d --build "${services[@]}" ;;
    stop) "${compose[@]}" stop "${services[@]}" ;;
    restart)
        "${compose[@]}" stop "${services[@]}" &&
            run_compose_with_podman_hint up -d --build "${services[@]}"
        ;;
    status) "${compose[@]}" ps "${services[@]}" ;;
    logs) "${compose[@]}" logs -f "${services[@]}" ;;
esac
