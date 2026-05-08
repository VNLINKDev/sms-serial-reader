#!/usr/bin/env bash
set -Eeuo pipefail
exec ./scripts/restore-db.sh "$@"
