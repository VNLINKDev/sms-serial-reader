#!/usr/bin/env bash

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.single.yml"

usage() {
    cat <<'EOF'
Quản lý một modem/SIM trên một host riêng.

Cách dùng:
  ./manage-single-sim.sh [tùy chọn] <lệnh>

Lệnh:
  start     Build image và khởi động service; bắt buộc có --env-file
  stop      Dừng service, không xóa container
  restart   Khởi động lại container hiện có, giữ nguyên cấu hình lúc start
  status    Hiển thị trạng thái container
  info      Hiển thị số SIM, port host và số nhận SMS keep-alive
  logs      Theo dõi log; nhấn Ctrl+C để thoát
  help      Hiển thị trợ giúp

Tùy chọn:
  --env-file <file>   File cấu hình SIM; bắt buộc khi chạy lệnh start
  --device <device>   USB serial trên host
                      Mặc định: /dev/ttyUSB0
  --log-dir <dir>     Thư mục log trên host
                      Mặc định: ./logs/single
  -h, --help          Hiển thị trợ giúp

Đường dẫn tương đối được tính từ thư mục deploy.
Trong container, device luôn được map thành /dev/modem.

Ví dụ:
  ./manage-single-sim.sh --env-file ./env/.envsim84832019510 start
  ./manage-single-sim.sh --env-file ./env/.envsim84812943652 start
  ./manage-single-sim.sh restart
  ./manage-single-sim.sh stop
  ./manage-single-sim.sh info
  ./manage-single-sim.sh logs
EOF
}

fail() {
    echo "[ERROR] $*" >&2
    exit 1
}

read_env_text_value() {
    local env_text="$1"
    local wanted_key="$2"
    local key value

    while IFS='=' read -r key value; do
        if [ "$key" = "$wanted_key" ]; then
            echo "$value"
            return 0
        fi
    done <<< "$env_text"

    echo "(chưa cấu hình)"
}

env_file=""
device="/dev/ttyUSB0"
log_dir="./logs/single"
command_name=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --env-file|--device|--log-dir)
            option="$1"
            [ "$#" -ge 2 ] || fail "Thiếu giá trị cho $option."
            case "$option" in
                --env-file) env_file="$2" ;;
                --device) device="$2" ;;
                --log-dir) log_dir="$2" ;;
            esac
            shift 2
            ;;
        help|-h|--help) usage; exit 0 ;;
        start|stop|restart|status|info|logs)
            [ -z "$command_name" ] || fail "Chỉ được chọn một lệnh."
            command_name="$1"
            shift
            ;;
        *) fail "Tham số không hợp lệ: $1. Dùng --help để xem hướng dẫn." ;;
    esac
done

[ -n "$command_name" ] || { usage >&2; fail "Chưa chọn lệnh."; }

resolve_from_deploy() {
    case "$1" in
        /*) echo "$1" ;;
        *) echo "$SCRIPT_DIR/${1#./}" ;;
    esac
}

log_dir="$(resolve_from_deploy "$log_dir")"

case "$command_name" in
    start)
        [ -n "$env_file" ] || fail "Lệnh start bắt buộc có --env-file <file>."
        env_file="$(resolve_from_deploy "$env_file")"
        [ -f "$env_file" ] || fail "Không tìm thấy file env: $env_file"
        [ -e "$device" ] || fail "Không tìm thấy USB serial: $device"
        mkdir -p "$log_dir" || fail "Không thể tạo thư mục log: $log_dir"
        ;;
esac

command -v docker >/dev/null 2>&1 || fail "Không tìm thấy lệnh docker."
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin chưa sẵn sàng."

[ -n "$env_file" ] && export SINGLE_SIM_ENV_FILE="$env_file"
export SINGLE_SIM_DEVICE="$device"
export SINGLE_SIM_LOG_DIR="$log_dir"

compose=(docker compose -f "$COMPOSE_FILE")

if [ "$command_name" = "info" ]; then
    container_id="$("${compose[@]}" ps -q sms-reader)"
    [ -n "$container_id" ] || fail "Container chưa tồn tại. Hãy chạy lệnh start trước."

    container_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id")"
    phone_number="$(read_env_text_value "$container_environment" PHONE_NUMBER)"
    keep_alive_phone_number="$(read_env_text_value "$container_environment" KEEP_ALIVE_PHONE_NUMBER)"
    host_device="$(docker inspect --format '{{range .HostConfig.Devices}}{{if eq .PathInContainer "/dev/modem"}}{{.PathOnHost}}{{end}}{{end}}' "$container_id")"
    [ -n "$host_device" ] || host_device="(không tìm thấy mapping)"

    echo "Nguồn: container hiện có"
    echo "  PHONE_NUMBER: $phone_number"
    echo "  Port trên host: $host_device"
    echo "  Port trong container: /dev/modem"
    echo "  KEEP_ALIVE_PHONE_NUMBER: $keep_alive_phone_number"
    exit 0
fi

case "$command_name" in
    start) "${compose[@]}" up -d --build sms-reader ;;
    stop) "${compose[@]}" stop sms-reader ;;
    restart) "${compose[@]}" restart sms-reader ;;
    status) "${compose[@]}" ps sms-reader ;;
    logs) "${compose[@]}" logs -f sms-reader ;;
esac
