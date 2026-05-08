#!/usr/bin/env bash
set -Eeuo pipefail
exec ./scripts/restart.sh "${1:-staging}"
