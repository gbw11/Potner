#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
ENV_FILE="${REPO_ROOT}/.env"
COMPOSE_FILE="${REPO_ROOT}/compose.yml"
PASSWORD_FILE="${REPO_ROOT}/infra/mosquitto/config/password.txt"

errors=0

ok() { printf '[OK] %s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*" >&2; errors=$((errors + 1)); }

printf 'Potner EC2 preflight (read-only)\n'
printf 'Repository: %s\n\n' "$REPO_ROOT"

if command -v id >/dev/null 2>&1; then
    printf 'Current user: %s\n' "$(id -un)"
else
    fail 'id command is unavailable.'
fi

if [[ -r /etc/os-release ]]; then
    printf 'OS: %s\n' "$(. /etc/os-release && printf '%s' "${PRETTY_NAME:-unknown}")"
else
    warn '/etc/os-release is unavailable.'
fi

if command -v docker >/dev/null 2>&1; then
    ok "Docker client found: $(docker --version)"
else
    fail 'Docker is not installed or is not on PATH.'
fi

compose_version=''
if command -v docker >/dev/null 2>&1 && compose_version=$(docker compose version 2>/dev/null); then
    if [[ "$compose_version" =~ v([0-9]+)\. ]]; then
        if (( BASH_REMATCH[1] >= 2 )); then
            ok "Docker Compose v2 found: $compose_version"
        else
            fail "Docker Compose v2 is required: $compose_version"
        fi
    else
        fail "Could not parse Docker Compose version: $compose_version"
    fi
else
    fail 'Docker Compose v2 is unavailable.'
fi

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ok 'Docker Engine is reachable.'
else
    fail 'Docker Engine is not running or the current user cannot access it.'
fi

if command -v free >/dev/null 2>&1; then
    printf 'Memory:\n'
    free -h
elif [[ -r /proc/meminfo ]]; then
    printf 'Memory:\n'
    awk '/MemTotal|MemAvailable/ {print}' /proc/meminfo
else
    warn 'Memory usage could not be inspected.'
fi

if command -v df >/dev/null 2>&1; then
    printf 'Disk usage:\n'
    df -h "$REPO_ROOT"
else
    warn 'df command is unavailable; disk usage could not be inspected.'
fi

port_in_use() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltnH 2>/dev/null | grep -Eq "[.:]${port}[[:space:]]"
    elif command -v netstat >/dev/null 2>&1; then
        netstat -ltn 2>/dev/null | grep -Eq "[.:]${port}[[:space:]]"
    else
        return 2
    fi
}

printf 'Listening port checks:\n'
for port in 22 80 443 8989 1883 3306 8080; do
    if port_in_use "$port"; then
        if [[ "$port" == 80 || "$port" == 1883 ]]; then
            fail "Port ${port} is already in use; Potner external binding may conflict."
        else
            warn "Port ${port} is in use; existing service was not changed."
        fi
    else
        result=$?
        if [[ "$result" == 2 ]]; then
            fail 'Neither ss nor netstat is available for port inspection.'
            break
        fi
        ok "Port ${port} is not listening."
    fi
done

if [[ -f "$ENV_FILE" ]]; then
    ok '.env exists.'
else
    fail '.env is missing; create it from .env.example with real deployment secrets.'
fi

if [[ -f "$COMPOSE_FILE" ]]; then
    ok 'compose.yml exists.'
else
    fail 'compose.yml is missing.'
fi

if [[ -f "$PASSWORD_FILE" ]]; then
    ok 'Mosquitto password.txt exists.'
else
    fail 'Mosquitto password.txt is missing; Mosquitto cannot start with allow_anonymous false.'
fi

required_vars=(
    COMPOSE_PROJECT_NAME POTNER_HTTP_PORT MQTT_PORT
    MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD MYSQL_ROOT_PASSWORD
    SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
    JWT_SECRET JWT_ACCESS_TOKEN_EXPIRATION JWT_REFRESH_TOKEN_EXPIRATION
    MQTT_HEALTH_USERNAME MQTT_HEALTH_PASSWORD
    MQTT_ENABLED MQTT_BROKER_URL MQTT_USERNAME MQTT_PASSWORD MQTT_CLIENT_ID
    MQTT_TOPIC MQTT_QOS MQTT_CONNECTION_TIMEOUT_SECONDS
    MQTT_KEEP_ALIVE_SECONDS MQTT_RECOVERY_INTERVAL_MS
)

read_env_value() {
    local key="$1"
    if [[ -f "$ENV_FILE" ]]; then
        awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$ENV_FILE"
    fi
}

if [[ -f "$ENV_FILE" ]]; then
    for variable in "${required_vars[@]}"; do
        value="$(read_env_value "$variable")"
        if [[ -n "${value//[[:space:]]/}" ]]; then
            ok "Required environment variable is present: ${variable}"
        else
            fail "Required environment variable is missing or empty in .env: ${variable}"
        fi
    done
fi

if command -v systemctl >/dev/null 2>&1; then
    if systemctl status gerrit.service --no-pager >/dev/null 2>&1; then
        ok 'gerrit.service status was queried (active).'
    else
        status_code=$?
        warn "gerrit.service status was queried (systemctl exit ${status_code}); no service change was made."
    fi
else
    warn 'systemctl is unavailable; gerrit.service status could not be queried.'
fi

if (( errors > 0 )); then
    printf '\nPreflight failed with %d issue(s). No server configuration was changed.\n' "$errors" >&2
    exit 1
fi

printf '\nPreflight passed. No server configuration was changed.\n'
