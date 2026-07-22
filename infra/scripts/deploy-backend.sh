#!/usr/bin/env sh

set -eu

NEW_IMAGE="${1:-}"

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-potner-infra-test}"
BACKEND_SERVICE="backend"
MYSQL_SERVICE="mysql"
MOSQUITTO_SERVICE="mosquitto"
NGINX_SERVICE="nginx"

ROLLBACK_IMAGE="${ROLLBACK_IMAGE:-potner-backend:rollback}"
HEALTH_MAX_ATTEMPTS="${HEALTH_MAX_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-5}"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)"

COMPOSE_FILE="${PROJECT_DIR}/compose.yml"
DEPLOY_COMPOSE_FILE="${PROJECT_DIR}/compose.deploy.yml"
DEPLOY_ENV_FILE="${PROJECT_DIR}/.env.deploy-runtime"

cleanup() {
    rm -f "$DEPLOY_ENV_FILE"
}

trap cleanup EXIT INT TERM

find_container() {
    service_name="$1"

    docker ps -a \
        --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
        --filter "label=com.docker.compose.service=${service_name}" \
        --format '{{.Names}}' |
        head -1
}

prepare_compose_environment() {
    mysql_container="$(find_container "$MYSQL_SERVICE")"
    mosquitto_container="$(find_container "$MOSQUITTO_SERVICE")"

    if [ -z "$mysql_container" ]; then
        echo "MySQL 컨테이너를 찾지 못했습니다."
        return 1
    fi

    if [ -z "$mosquitto_container" ]; then
        echo "Mosquitto 컨테이너를 찾지 못했습니다."
        return 1
    fi

    docker inspect \
        --format '{{range .Config.Env}}{{println .}}{{end}}' \
        "$mysql_container" |
        grep -E '^(MYSQL_DATABASE|MYSQL_USER|MYSQL_PASSWORD|MYSQL_ROOT_PASSWORD)=' \
        > "$DEPLOY_ENV_FILE"

    docker inspect \
        --format '{{range .Config.Env}}{{println .}}{{end}}' \
        "$mosquitto_container" |
        grep -E '^(MQTT_HEALTH_USERNAME|MQTT_HEALTH_PASSWORD)=' \
        >> "$DEPLOY_ENV_FILE"

    if [ -z "${JWT_SECRET:-}" ]; then
        echo "JWT_SECRET environment variable is required."
        return 1
    fi

    if [ "${MQTT_ENABLED:-false}" = "true" ] &&
       { [ -z "${MQTT_USERNAME:-}" ] || [ -z "${MQTT_PASSWORD:-}" ]; }; then
        echo "MQTT_USERNAME and MQTT_PASSWORD are required when MQTT is enabled."
        return 1
    fi

    printf '%s\n' \
        "JWT_SECRET=${JWT_SECRET}" \
        "JWT_ACCESS_TOKEN_EXPIRATION=${JWT_ACCESS_TOKEN_EXPIRATION:-1800000}" \
        "JWT_REFRESH_TOKEN_EXPIRATION=${JWT_REFRESH_TOKEN_EXPIRATION:-1209600000}" \
        "MQTT_ENABLED=${MQTT_ENABLED:-false}" \
        "MQTT_BROKER_URL=${MQTT_BROKER_URL:-tcp://mosquitto:1883}" \
        "MQTT_USERNAME=${MQTT_USERNAME:-}" \
        "MQTT_PASSWORD=${MQTT_PASSWORD:-}" \
        "MQTT_CLIENT_ID=${MQTT_CLIENT_ID:-potner-backend-prod}" \
        "MQTT_TOPIC=${MQTT_TOPIC:-potner/device/+/sensor/#}" \
        "MQTT_QOS=${MQTT_QOS:-1}" \
        "MQTT_CONNECTION_TIMEOUT_SECONDS=${MQTT_CONNECTION_TIMEOUT_SECONDS:-10}" \
        "MQTT_KEEP_ALIVE_SECONDS=${MQTT_KEEP_ALIVE_SECONDS:-60}" \
        "MQTT_RECOVERY_INTERVAL_MS=${MQTT_RECOVERY_INTERVAL_MS:-10000}" \
        >> "$DEPLOY_ENV_FILE"

    env_count="$(wc -l < "$DEPLOY_ENV_FILE" | tr -d ' ')"

    if [ "$env_count" -lt 19 ]; then
        echo "Compose 실행에 필요한 환경변수를 모두 가져오지 못했습니다."
        return 1
    fi

    chmod 600 "$DEPLOY_ENV_FILE"
}

compose_backend_up() {
    image_name="$1"

    BACKEND_IMAGE="$image_name" \
        docker compose \
        --project-name "$PROJECT_NAME" \
        --env-file "$DEPLOY_ENV_FILE" \
        -f "$COMPOSE_FILE" \
        -f "$DEPLOY_COMPOSE_FILE" \
        up -d \
        --no-deps \
        --force-recreate \
        --no-build \
        "$BACKEND_SERVICE"
}

wait_for_backend_health() {
    attempt=1

    while [ "$attempt" -le "$HEALTH_MAX_ATTEMPTS" ]; do
        backend_container="$(find_container "$BACKEND_SERVICE")"

        if [ -z "$backend_container" ]; then
            echo "백엔드 컨테이너를 찾지 못했습니다."
            return 1
        fi

        container_status="$(
            docker inspect \
                --format '{{.State.Status}}' \
                "$backend_container" 2>/dev/null || true
        )"

        health_status="$(
            docker inspect \
                --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
                "$backend_container" 2>/dev/null || true
        )"

        if [ "$health_status" = "healthy" ]; then
            echo "백엔드 Health Check 성공"
            return 0
        fi

        if [ "$container_status" = "exited" ] ||
           [ "$container_status" = "dead" ] ||
           [ "$container_status" = "restarting" ]; then
            echo "백엔드 컨테이너가 비정상 상태입니다: $container_status"
            docker logs --tail 200 "$backend_container" || true
            return 1
        fi

        if [ "$health_status" = "unhealthy" ]; then
            echo "백엔드 Health Check가 unhealthy 상태입니다."
            docker logs --tail 200 "$backend_container" || true
            return 1
        fi

        echo "백엔드 Health Check 대기 중: ${attempt}/${HEALTH_MAX_ATTEMPTS}"
        attempt=$((attempt + 1))
        sleep "$HEALTH_INTERVAL_SECONDS"
    done

    backend_container="$(find_container "$BACKEND_SERVICE")"

    echo "백엔드 Health Check 시간이 초과되었습니다."
    docker logs --tail 200 "$backend_container" || true
    return 1
}

reload_and_check_nginx() {
    nginx_container="$(find_container "$NGINX_SERVICE")"

    if [ -z "$nginx_container" ]; then
        echo "Nginx 컨테이너를 찾지 못했습니다."
        return 1
    fi

    docker exec "$nginx_container" nginx -s reload
    sleep 2

    docker exec "$nginx_container" \
        wget -qO- http://127.0.0.1/actuator/health \
        >/dev/null

    echo "Nginx를 통한 운영 Health Check 성공"
}

rollback_backend() {
    echo "이전 백엔드 이미지로 롤백을 시작합니다."

    if ! compose_backend_up "$ROLLBACK_IMAGE"; then
        echo "이전 이미지 컨테이너 실행에 실패했습니다."
        return 1
    fi

    if ! wait_for_backend_health; then
        echo "롤백 백엔드 Health Check에 실패했습니다."
        return 1
    fi

    if ! reload_and_check_nginx; then
        echo "롤백 후 Nginx Health Check에 실패했습니다."
        return 1
    fi

    echo "이전 백엔드 이미지로 롤백했습니다."
}

main() {
    if [ -z "$NEW_IMAGE" ]; then
        echo "사용법: $0 <배포할 Docker 이미지>"
        exit 1
    fi

    if [ ! -f "$COMPOSE_FILE" ]; then
        echo "compose.yml을 찾지 못했습니다: $COMPOSE_FILE"
        exit 1
    fi

    if [ ! -f "$DEPLOY_COMPOSE_FILE" ]; then
        echo "compose.deploy.yml을 찾지 못했습니다: $DEPLOY_COMPOSE_FILE"
        exit 1
    fi

    if ! docker image inspect "$NEW_IMAGE" >/dev/null 2>&1; then
        echo "배포할 Docker 이미지를 찾지 못했습니다: $NEW_IMAGE"
        exit 1
    fi

    current_backend_container="$(find_container "$BACKEND_SERVICE")"

    if [ -z "$current_backend_container" ]; then
        echo "현재 운영 백엔드 컨테이너를 찾지 못했습니다."
        exit 1
    fi

    current_image_id="$(
        docker inspect \
            --format '{{.Image}}' \
            "$current_backend_container"
    )"

    echo "현재 운영 백엔드: $current_backend_container"
    echo "새 배포 이미지: $NEW_IMAGE"

    docker tag "$current_image_id" "$ROLLBACK_IMAGE"
    echo "롤백 이미지 준비 완료: $ROLLBACK_IMAGE"

    prepare_compose_environment

    deployment_failed=0

    if ! compose_backend_up "$NEW_IMAGE"; then
        echo "새 백엔드 컨테이너 실행에 실패했습니다."
        deployment_failed=1
    elif ! wait_for_backend_health; then
        deployment_failed=1
    elif ! reload_and_check_nginx; then
        deployment_failed=1
    fi

    if [ "$deployment_failed" -ne 0 ]; then
        echo "새 백엔드 배포에 실패했습니다."

        if rollback_backend; then
            echo "롤백은 성공했지만 새 버전 배포는 실패했습니다."
        else
            echo "새 버전 배포와 롤백이 모두 실패했습니다."
        fi

        exit 1
    fi

    echo "운영 백엔드 배포가 완료되었습니다."
    echo "배포 이미지: $NEW_IMAGE"
}

main "$@"
