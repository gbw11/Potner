#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
ENV_FILE="${REPO_ROOT}/.env"

if [[ ! -f "$ENV_FILE" ]]; then
    printf '[FAIL] .env is missing.\n' >&2
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    printf '[FAIL] Docker is unavailable.\n' >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    printf '[FAIL] curl is required for HTTP health checks.\n' >&2
    exit 1
fi

POTNER_HTTP_PORT="${POTNER_HTTP_PORT:-80}"
compose=(docker compose --env-file "$ENV_FILE" -f "$REPO_ROOT/compose.yml")

printf '%s\n' '[INFO] docker compose ps'
"${compose[@]}" ps

for service in mysql mosquitto backend nginx; do
    container_id="$("${compose[@]}" ps -q "$service")"
    if [[ -z "$container_id" ]]; then
        printf '[FAIL] %s container is not created.\n' "$service" >&2
        exit 1
    fi

    state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
    if [[ "$state" != running ]]; then
        printf '[FAIL] %s container state is %s.\n' "$service" "$state" >&2
        exit 1
    fi

    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")"
    if [[ "$health" != healthy ]]; then
        printf '[FAIL] %s container health is %s.\n' "$service" "$health" >&2
        exit 1
    fi
    printf '[OK] %s container is running and healthy.\n' "$service"
done

base_url="http://127.0.0.1:${POTNER_HTTP_PORT}"
for endpoint in /api/v1/health /actuator/health; do
    if curl --fail --silent --show-error --max-time 10 "${base_url}${endpoint}" >/dev/null; then
        printf '[OK] %s is healthy through Nginx.\n' "$endpoint"
    else
        printf '[FAIL] %s failed through Nginx.\n' "$endpoint" >&2
        exit 1
    fi
done

printf '%s\n' '[OK] Potner health check passed.'
