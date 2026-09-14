#!/usr/bin/env sh

set -eu

NEW_IMAGE="${1:-}"

PROJECT_NAME="${COMPOSE_PROJECT_NAME:-potner-infra-test}"
BACKEND_SERVICE="backend"
MYSQL_SERVICE="mysql"
MOSQUITTO_SERVICE="mosquitto"
NGINX_SERVICE="nginx"
YOLO_SERVICE="yolo"

ROLLBACK_IMAGE="${ROLLBACK_IMAGE:-potner-backend:rollback}"
YOLO_ROLLBACK_IMAGE="${YOLO_ROLLBACK_IMAGE:-potner-yolo:rollback}"
HEALTH_MAX_ATTEMPTS="${HEALTH_MAX_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-5}"

# 추론 서비스는 모델 적재에 시간이 걸리고, 그동안 /ready 가 503 을 낸다.
YOLO_READY_MAX_ATTEMPTS="${YOLO_READY_MAX_ATTEMPTS:-24}"
YOLO_READY_INTERVAL_SECONDS="${YOLO_READY_INTERVAL_SECONDS:-5}"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

# 배포는 항상 고정된 배포 디렉토리 기준으로 수행한다.
# POTNER_DEPLOY_DIR을 주지 않으면 스크립트 위치를 기준으로 추론한다.
PROJECT_DIR="${POTNER_DEPLOY_DIR:-$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)}"

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
        "MQTT_TOPIC=${MQTT_TOPIC:-potner/device/+/sensor/telemetry}" \
        "MQTT_QOS=${MQTT_QOS:-1}" \
        "MQTT_CONNECTION_TIMEOUT_SECONDS=${MQTT_CONNECTION_TIMEOUT_SECONDS:-10}" \
        "MQTT_KEEP_ALIVE_SECONDS=${MQTT_KEEP_ALIVE_SECONDS:-60}" \
        "MQTT_RECOVERY_INTERVAL_MS=${MQTT_RECOVERY_INTERVAL_MS:-10000}" \
        "FIREBASE_CREDENTIALS_PATH=${FIREBASE_CREDENTIALS_PATH:-}" \
        "LLM_API_KEY=${LLM_API_KEY:-}" \
        "LLM_BASE_URL=${LLM_BASE_URL:-https://gms.ssafy.io/gmsapi/api.openai.com/v1}" \
        "LLM_MODEL=${LLM_MODEL:-gpt-4.1-mini}" \
        "VISION_ENABLED=${VISION_ENABLED:-false}" \
        "VISION_REQUEST_CONFIDENCE=${VISION_REQUEST_CONFIDENCE:-0.25}" \
        "VISION_MIN_CONFIDENCE=${VISION_MIN_CONFIDENCE:-0.60}" \
        "VISION_AUTO_ADVANCE_ENABLED=${VISION_AUTO_ADVANCE_ENABLED:-false}" \
        >> "$DEPLOY_ENV_FILE"

    env_count="$(wc -l < "$DEPLOY_ENV_FILE" | tr -d ' ')"

    if [ "$env_count" -lt 27 ]; then
        echo "Compose 실행에 필요한 환경변수를 모두 가져오지 못했습니다."
        return 1
    fi

    chmod 600 "$DEPLOY_ENV_FILE"
}

compose_backend_up() {
    image_name="$1"

    BACKEND_IMAGE="$image_name" \
    YOLO_IMAGE="$YOLO_IMAGE" \
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

# 추론 서비스를 올린다. --force-recreate 를 쓰지 않는다. 태그가 tree 해시라서 디렉터리가
# 바뀌지 않았으면 compose 가 아무 일도 하지 않아야 하고, 그래야 배포마다 모델을 다시
# 적재하지 않는다.
compose_yolo_up() {
    image_name="$1"

    BACKEND_IMAGE="$BACKEND_IMAGE_FOR_PARSING" \
    YOLO_IMAGE="$image_name" \
        docker compose \
        --project-name "$PROJECT_NAME" \
        --env-file "$DEPLOY_ENV_FILE" \
        -f "$COMPOSE_FILE" \
        -f "$DEPLOY_COMPOSE_FILE" \
        up -d \
        --no-deps \
        --no-build \
        "$YOLO_SERVICE"
}

# /health 로는 부족하다. 모델 적재가 실패해도 앱은 살아 있고 /health 는 up 을 낸다.
# 컨테이너 healthcheck 도 /health 를 보므로 healthy 로 표시된다. /ready 만이 모델이 실제로
# 올라왔는지 알려주며, classes 까지 확인하면 엉뚱한 가중치가 들어간 것도 잡힌다.
wait_for_yolo_ready() {
    attempt=1

    while [ "$attempt" -le "$YOLO_READY_MAX_ATTEMPTS" ]; do
        yolo_container="$(find_container "$YOLO_SERVICE")"

        if [ -z "$yolo_container" ]; then
            echo "추론 컨테이너를 찾지 못했습니다."
            return 1
        fi

        container_status="$(
            docker inspect \
                --format '{{.State.Status}}' \
                "$yolo_container" 2>/dev/null || true
        )"

        if [ "$container_status" = "exited" ] ||
           [ "$container_status" = "dead" ]; then
            echo "추론 컨테이너가 비정상 상태입니다: $container_status"
            docker logs --tail 200 "$yolo_container" || true
            return 1
        fi

        if docker exec "$yolo_container" python -c '
import json
import sys
import urllib.request

with urllib.request.urlopen("http://127.0.0.1:8000/ready", timeout=5) as response:
    body = json.load(response)

expected_classes = {"0": "germination", "1": "vegetative", "2": "flowering"}

if body.get("ready") is not True:
    sys.exit("ready is not true")
if body.get("device") != "cpu":
    sys.exit("device is not cpu: %s" % body.get("device"))
if body.get("classes") != expected_classes:
    sys.exit("classes mismatch: %s" % body.get("classes"))
' >/dev/null 2>&1; then
            echo "추론 서비스 준비 확인 성공"
            return 0
        fi

        echo "추론 서비스 준비 대기 중: ${attempt}/${YOLO_READY_MAX_ATTEMPTS}"
        attempt=$((attempt + 1))
        sleep "$YOLO_READY_INTERVAL_SECONDS"
    done

    yolo_container="$(find_container "$YOLO_SERVICE")"

    echo "추론 서비스 준비 확인에 실패했습니다."
    if [ -n "$yolo_container" ]; then
        # 실패 이유를 남긴다. 위 검사는 조용히 돌리므로 여기서 한 번 그대로 보여준다.
        docker exec "$yolo_container" python -c '
import json
import urllib.request

with urllib.request.urlopen("http://127.0.0.1:8000/ready", timeout=5) as response:
    print(json.load(response))
' || true
        docker logs --tail 200 "$yolo_container" || true
    fi
    return 1
}

# 추론 서비스를 backend 보다 먼저 올린다. 여기서 실패하면 backend 는 건드리지 않으므로
# 되돌릴 것이 추론 서비스뿐이다.
deploy_yolo() {
    current_yolo_container="$(find_container "$YOLO_SERVICE")"
    yolo_rollback_ready=0

    if [ -n "$current_yolo_container" ]; then
        current_yolo_image_id="$(
            docker inspect --format '{{.Image}}' "$current_yolo_container"
        )"
        docker tag "$current_yolo_image_id" "$YOLO_ROLLBACK_IMAGE"
        yolo_rollback_ready=1
        echo "추론 롤백 이미지 준비 완료: $YOLO_ROLLBACK_IMAGE"
    else
        echo "실행 중인 추론 컨테이너가 없습니다. 첫 배포로 처리합니다."
    fi

    if compose_yolo_up "$YOLO_IMAGE" && wait_for_yolo_ready; then
        echo "추론 서비스 배포가 완료되었습니다: $YOLO_IMAGE"
        return 0
    fi

    echo "추론 서비스 배포에 실패했습니다."

    if [ "$yolo_rollback_ready" -ne 1 ]; then
        echo "되돌릴 이전 추론 이미지가 없습니다."
        return 1
    fi

    echo "이전 추론 이미지로 롤백을 시작합니다."

    # 롤백은 태그가 같아도 컨테이너를 반드시 바꿔야 하므로 강제로 재생성한다.
    if BACKEND_IMAGE="$BACKEND_IMAGE_FOR_PARSING" \
       YOLO_IMAGE="$YOLO_ROLLBACK_IMAGE" \
        docker compose \
        --project-name "$PROJECT_NAME" \
        --env-file "$DEPLOY_ENV_FILE" \
        -f "$COMPOSE_FILE" \
        -f "$DEPLOY_COMPOSE_FILE" \
        up -d --no-deps --force-recreate --no-build "$YOLO_SERVICE" &&
        wait_for_yolo_ready; then
        echo "이전 추론 이미지로 롤백했습니다."
    else
        echo "추론 서비스 롤백도 실패했습니다. 추론이 중단된 상태입니다."
    fi

    return 1
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

# 설정 파일 변경은 reload로 적용되지만 볼륨·포트 같은 컨테이너 구성 변경은 재생성이 필요하다.
# compose는 서비스 정의가 바뀐 경우에만 재생성하므로 평소 배포에서는 아무 일도 하지 않는다.
#
# nginx만 올리더라도 BACKEND_IMAGE를 넘겨야 한다. compose는 대상 서비스를 고르기 전에
# 파일 전체를 해석하고, compose.deploy.yml이 backend.image에 그 변수를 요구하기 때문이다.
compose_nginx_up() {
    image_name="$1"

    BACKEND_IMAGE="$image_name" \
    YOLO_IMAGE="$YOLO_IMAGE" \
        docker compose \
        --project-name "$PROJECT_NAME" \
        --env-file "$DEPLOY_ENV_FILE" \
        -f "$COMPOSE_FILE" \
        -f "$DEPLOY_COMPOSE_FILE" \
        up -d \
        --no-deps \
        --no-build \
        "$NGINX_SERVICE"
}

# 인자로 받은 이미지는 nginx 기동에 쓰이지 않는다. compose 해석을 통과시키기 위한 값이며
# 지금 배포 중인 이미지와 같아야 backend 서비스 정의가 실행 중인 컨테이너와 어긋나지 않는다.
reload_and_check_nginx() {
    if ! compose_nginx_up "$1"; then
        echo "Nginx 컨테이너 구성 적용에 실패했습니다."
        return 1
    fi

    nginx_container="$(find_container "$NGINX_SERVICE")"

    if [ -z "$nginx_container" ]; then
        echo "Nginx 컨테이너를 찾지 못했습니다."
        return 1
    fi

    # 재생성된 경우 새 컨테이너가 이미 최신 설정으로 떠 있어 reload가 필요하지 않고,
    # 기동 직후여서 실패할 수도 있다. 재생성되지 않았을 때 동기화된 설정 파일을 반영하는 것이
    # 목적이므로 실패는 무시하고 아래 Health Check로 판정한다.
    docker exec "$nginx_container" nginx -s reload || true

    attempt=1

    while [ "$attempt" -le "$HEALTH_MAX_ATTEMPTS" ]; do
        if docker exec "$nginx_container" \
            wget -qO- http://127.0.0.1/actuator/health >/dev/null 2>&1; then
            echo "Nginx를 통한 운영 Health Check 성공"
            return 0
        fi

        echo "Nginx Health Check 대기 중: ${attempt}/${HEALTH_MAX_ATTEMPTS}"
        attempt=$((attempt + 1))
        sleep "$HEALTH_INTERVAL_SECONDS"
    done

    echo "Nginx Health Check 시간이 초과되었습니다."
    docker logs --tail 200 "$nginx_container" || true
    return 1
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

    if ! reload_and_check_nginx "$ROLLBACK_IMAGE"; then
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

    # compose.deploy.yml 이 yolo.image 에 이 값을 요구한다. 비어 있으면 backend 만 올리려
    # 해도 compose 가 파일 전체를 해석하다 실패한다.
    if [ -z "${YOLO_IMAGE:-}" ]; then
        echo "YOLO_IMAGE environment variable is required."
        exit 1
    fi

    if ! docker image inspect "$YOLO_IMAGE" >/dev/null 2>&1; then
        echo "추론 Docker 이미지를 찾지 못했습니다: $YOLO_IMAGE"
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

    # 추론 서비스만 올릴 때도 compose 는 backend.image 를 요구한다. 지금 돌고 있는 이미지를
    # 넘겨야 backend 서비스 정의가 실행 중인 컨테이너와 어긋나지 않는다.
    BACKEND_IMAGE_FOR_PARSING="$ROLLBACK_IMAGE"

    prepare_compose_environment

    # 추론 서비스를 먼저 올린다. 여기서 실패하면 backend 를 건드리지 않은 상태라 되돌릴
    # 것이 추론 서비스뿐이고, 운영 백엔드는 그대로 살아 있다.
    if ! deploy_yolo; then
        echo "추론 서비스 배포가 실패해 백엔드 배포를 진행하지 않습니다."
        exit 1
    fi

    deployment_failed=0

    if ! compose_backend_up "$NEW_IMAGE"; then
        echo "새 백엔드 컨테이너 실행에 실패했습니다."
        deployment_failed=1
    elif ! wait_for_backend_health; then
        deployment_failed=1
    elif ! reload_and_check_nginx "$NEW_IMAGE"; then
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
