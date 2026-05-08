#!/usr/bin/env bash
set -Eeuo pipefail
exec ./scripts/deploy.sh "${1:-staging}"
