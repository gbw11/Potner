#!/usr/bin/env bash
# Pi 호스트 터미널에서 실행 (Cursor Agent 샌드박스 밖).
# 기대 배너: mqtt=on ... hb=30s water=on ... capture=on ...
set -euo pipefail
cd "$(dirname "$0")/.."
unset DEVICE_ID
set -a
# shellcheck disable=SC1091
source ./.env
set +a
if [[ -n "${DEVICE_ID:-}" ]]; then
  echo "ERROR: DEVICE_ID is set to '$DEVICE_ID' — unset it first" >&2
  exit 1
fi
exec .venv/bin/python -u main.py --config config/raspberry_pi.yaml
