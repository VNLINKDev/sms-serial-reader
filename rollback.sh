#!/usr/bin/env bash
set -Eeuo pipefail
exec ./scripts/rollback.sh "${1:-staging}"
