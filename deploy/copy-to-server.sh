#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REMOTE_USER="hoanganh.nguyen"
REMOTE_HOST="10.10.10.5"
REMOTE_PORT="22"
REMOTE_DIR="./smsotpv2"
REMOTE="${REMOTE_USER}@${REMOTE_HOST}"

files=(
    .dockerignore
    Dockerfile
    docker-compose.yml
    docker-compose.single.yml
    manage-multi-sim.sh
    manage-single-sim.sh
    sms-serial-reader.jar
)

for file in "${files[@]}"; do
    [ -f "$SCRIPT_DIR/$file" ] || {
        echo "[ERROR] Thiếu file deploy: $SCRIPT_DIR/$file" >&2
        exit 1
    }
done

[ -d "$SCRIPT_DIR/env" ] || {
    echo "[ERROR] Thiếu thư mục env: $SCRIPT_DIR/env" >&2
    exit 1
}

echo "Copy deploy payload tới ${REMOTE}:${REMOTE_DIR}"
ssh -p "$REMOTE_PORT" "$REMOTE" "mkdir -p '$REMOTE_DIR/env'"

sources=()
for file in "${files[@]}"; do
    sources+=("$SCRIPT_DIR/$file")
done

scp -P "$REMOTE_PORT" "${sources[@]}" "$REMOTE:$REMOTE_DIR/"
scp -P "$REMOTE_PORT" "$SCRIPT_DIR"/env/* "$REMOTE:$REMOTE_DIR/env/"

ssh -p "$REMOTE_PORT" "$REMOTE" \
    "chmod +x '$REMOTE_DIR/manage-multi-sim.sh' '$REMOTE_DIR/manage-single-sim.sh'"

echo "Hoàn tất: ${REMOTE}:${REMOTE_DIR}"
