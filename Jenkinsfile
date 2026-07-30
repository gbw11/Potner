// GitLab에 빌드 결과를 알려 Merge Request 화면에서 통과 여부를 볼 수 있게 한다.
// 상태 보고는 부가 기능이므로 실패해도 빌드를 중단시키지 않는다.
def reportGitlabStatus(String state) {
    try {
        updateGitlabCommitStatus name: 'jenkins', state: state
        echo "GitLab 커밋 상태 보고: ${state}"
    } catch (Exception e) {
        echo "GitLab 커밋 상태 보고 실패(빌드에는 영향 없음): ${e.message}"
    }
}

pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        gitLabConnection('SSAFY GitLab')
    }

    environment {
        TESTCONTAINERS_HOST_OVERRIDE = '127.0.0.1'
        TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = '/var/run/docker.sock'
        JWT_SECRET = credentials('potner-jwt-secret')
        JWT_ACCESS_TOKEN_EXPIRATION = '1800000'
        JWT_REFRESH_TOKEN_EXPIRATION = '1209600000'
        MQTT_BACKEND_CREDENTIALS = credentials('potner-mqtt-backend')
        MQTT_ENABLED = 'true'
        MQTT_BROKER_URL = 'tcp://mosquitto:1883'
        MQTT_CLIENT_ID = 'potner-backend-prod'
        MQTT_TOPIC = 'potner/device/+/sensor/telemetry'
        MQTT_QOS = '1'
        MQTT_CONNECTION_TIMEOUT_SECONDS = '10'
        MQTT_KEEP_ALIVE_SECONDS = '60'
        MQTT_RECOVERY_INTERVAL_MS = '10000'

        // Firebase 서비스 계정 키를 담는 named volume 과 컨테이너 안 경로다.
        // compose.yml 의 firebase-credentials 볼륨 정의·마운트 경로와 맞아야 한다.
        // 임시 CI 컨테이너에는 넘기지 않는다. 키 없이도 기동되는지가 검증 대상이다.
        FIREBASE_CREDENTIALS_VOLUME = 'potner-firebase-credentials'
        FIREBASE_CREDENTIALS_MOUNT = '/var/potner/secrets'
        FIREBASE_CREDENTIALS_FILE_NAME = 'firebase-service-account.json'
        FIREBASE_CREDENTIALS_ID = 'potner-firebase-service-account'

        // backend 이미지가 애플리케이션을 실행하는 계정이다. backend/Dockerfile 의 USER 와
        // 같아야 한다. 키 파일 소유자를 이 값으로 맞추지 않으면 애플리케이션이 읽지 못한다.
        BACKEND_APP_UID = '10001'

        // SSAFY GMS 텍스트 생성 설정. 키는 Jenkins Credential(Secret text)에서 온다.
        // 임시 CI 컨테이너에는 넘기지 않는다. 키 없이도 기동되는지가 검증 대상이다.
        //
        // 모델과 엔드포인트를 여기 두는 이유는 비용이나 정원 문제로 모델을 바꿀 때
        // 이미지를 다시 만들지 않고 배포만 다시 돌리면 되게 하려는 것이다.
        LLM_CREDENTIALS_ID = 'potner-llm-api-key'
        LLM_BASE_URL = 'https://gms.ssafy.io/gmsapi/api.openai.com/v1'
        LLM_MODEL = 'gpt-4.1-mini'

        // 사진 기반 생장 단계 판정. 추론 서비스는 같은 네트워크의 yolo 컨테이너다.
        // 이 값이 없으면 애플리케이션 기본값이 꺼짐이라 분류기가 no-op 으로 떨어진다.
        //
        // 임시 CI 컨테이너에는 넘기지 않는다. .ci-backend.env 목록에 없으므로 자동으로 그렇게
        // 된다. 추론을 부르지 않고도 기동되는지가 검증 대상이고, CI 가 부르면 CPU 추론이라
        // 운영 판정과 자원을 다툰다. Firebase 키와 LLM 키를 넘기지 않는 것과 같은 방침이다.
        //
        // 임계값을 여기 두는 이유는 모델을 바꾸거나 오탐이 잦을 때 이미지를 다시 만들지 않고
        // 배포만 다시 돌리면 되게 하려는 것이다. LLM_MODEL 을 여기 둔 것과 같은 이유다.
        //   request-confidence : 추론 서비스에 넘길 하한. 무엇을 받아올지 정한다
        //   min-confidence     : 받아온 검출 중 무엇을 믿을지 정한다
        VISION_ENABLED = 'true'
        VISION_REQUEST_CONFIDENCE = '0.25'
        VISION_MIN_CONFIDENCE = '0.60'

        // 자동 승급은 꺼 둔다. 켜면 사용자가 손대지 않아도 생장 단계가 바뀌고 푸시가 나가는데
        // 되돌릴 수단이 없다. 꺼진 상태에서도 판정과 저장은 그대로 돌아
        // photo_growth_analysis 에 AUTO_ADVANCE_DISABLED 가 쌓이므로, 그 이력으로 오탐률을
        // 확인하고 VISION_MIN_CONFIDENCE 를 확정한 뒤 이 값을 true 로 바꾼다.
        VISION_AUTO_ADVANCE_ENABLED = 'false'

        // Multibranch에서는 브랜치마다 빌드 번호가 따로 매겨진다.
        // 서로 다른 브랜치가 동시에 빌드될 때 임시 이미지와 컨테이너 이름이
        // 겹치지 않도록 브랜치 이름을 함께 넣는다.
        BRANCH_SLUG = "${(env.BRANCH_NAME ?: 'single').replaceAll('[^A-Za-z0-9]', '-').toLowerCase()}"

        CI_IMAGE = "potner-backend-ci:${BRANCH_SLUG}-${BUILD_NUMBER}"
        CI_CONTAINER = "potner-backend-ci-${BRANCH_SLUG}-${BUILD_NUMBER}"
        DOCKER_NETWORK = 'potner-network'

        // 임시 컨테이너 전용 스키마. 운영 스키마에 Flyway가 마이그레이션을 적용하지 않도록 분리한다.
        // MySQL 식별자로 쓰이므로 BRANCH_SLUG와 달리 하이픈 대신 밑줄을 쓴다.
        // 브랜치별로 나누는 이유는 disableConcurrentBuilds가 브랜치 단위라
        // 서로 다른 브랜치가 동시에 빌드될 수 있기 때문이다.
        CI_DB_NAME = "potner_ci_${(env.BRANCH_NAME ?: 'single').replaceAll('[^A-Za-z0-9]', '_').toLowerCase()}"

        // 운영 배포가 이루어지는 고정 디렉토리.
        // Jenkins 컨테이너와 EC2 호스트가 같은 경로로 마운트하고 있어야 한다.
        DEPLOY_DIR = "${env.POTNER_DEPLOY_DIR ?: '/home/ubuntu/potner-infra-test'}"

        // 배포 대상 Compose 프로젝트. deploy-backend.sh도 이 값을 사용한다.
        COMPOSE_PROJECT_NAME = 'potner-infra-test'

        // Multibranch 잡은 BRANCH_NAME이 채워지고, 클래식 단일 브랜치 잡은 비어 있다.
        // 클래식 잡은 SCM 설정상 Server-master만 빌드하므로 배포 대상으로 간주한다.
        // Multibranch 전환 후에는 when { branch 'Server-master' }로 단순화할 수 있다.
        IS_DEPLOY_TARGET = "${(env.BRANCH_NAME == null || env.BRANCH_NAME == 'Server-master') ? 'true' : 'false'}"
    }

    stages {
        stage('Checkout Info') {
            steps {
                script {
                    reportGitlabStatus('running')
                }

                echo "Branch: ${env.BRANCH_NAME ?: 'configured SCM branch'}"
                echo "Workspace: ${env.WORKSPACE}"
                echo "CI image: ${env.CI_IMAGE}"

                sh '''
                    set -eux

                    git --version
                    git log -1 --oneline
                    find . -maxdepth 2 -type f | sort | head -100
                '''
            }
        }

        stage('Backend Test') {
            steps {
                sh '''
                    set -eux

                    test -f backend/gradlew
                    chmod +x backend/gradlew

                    docker run --rm \
                      --network host \
                      --volumes-from potner-jenkins \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      -w "$WORKSPACE/backend" \
                      -e GRADLE_USER_HOME=/tmp/gradle-home \
                      -e TESTCONTAINERS_HOST_OVERRIDE="$TESTCONTAINERS_HOST_OVERRIDE" \
                      -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="$TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE" \
                      -e JWT_SECRET="$JWT_SECRET" \
                      eclipse-temurin:21-jdk \
                      ./gradlew clean test integrationTest --no-daemon
                '''
            }
        }

        // 가중치는 5MB 남짓이라 git 에 그대로 커밋돼 있다. 파일이 깨진 채 커밋되면 컨테이너는
        // 뜨지만 /ready 가 계속 실패한다. 배포 전에 값싸게 걸러낸다.
        stage('Verify Inference Weights') {
            steps {
                sh '''
                    set -eux

                    cd yolo_cpu_handoff/models
                    sha256sum -c SHA256SUMS
                '''
            }
        }

        stage('Docker Image Build') {
            steps {
                sh '''
                    set -eux

                    docker build \
                      --tag "$CI_IMAGE" \
                      backend

                    docker image inspect "$CI_IMAGE"
                '''
            }
        }

        stage('Prepare Temporary Environment') {
            steps {
                sh '''
                    set -eu

                    SOURCE_BACKEND_CONTAINER="$(
                        docker ps \
                          --filter "label=com.docker.compose.service=backend" \
                          --format '{{.Names}}' \
                        | head -1
                    )"

                    if [ -z "$SOURCE_BACKEND_CONTAINER" ]; then
                        echo "실행 중인 Compose backend 컨테이너를 찾지 못했습니다."
                        exit 1
                    fi

                    echo "환경 설정을 가져올 컨테이너: $SOURCE_BACKEND_CONTAINER"

                    MYSQL_CONTAINER="$(
                        docker ps \
                          --filter "label=com.docker.compose.service=mysql" \
                          --format '{{.Names}}' \
                        | head -1
                    )"

                    if [ -z "$MYSQL_CONTAINER" ]; then
                        echo "실행 중인 Compose mysql 컨테이너를 찾지 못했습니다."
                        exit 1
                    fi

                    # 임시 컨테이너가 운영 스키마를 쓰면 Flyway가 운영 DB에 마이그레이션을 적용한다.
                    # 머지도 리뷰도 되지 않은 브랜치가 운영 스키마를 바꾸게 되므로 전용 스키마를 쓴다.
                    # 매 빌드 비운 스키마로 시작하므로 V1부터 전부 실행되어, 신규 환경 구축이
                    # 되는지도 함께 검증된다.
                    # 여기서만 추적을 끈다. Jenkins sh 스텝은 기본이 `#!/bin/sh -xe` 라
                    # set -eu 를 써도 -x 는 살아 있고, 셸은 대입문을 값까지 그대로 찍는다.
                    # JWT_SECRET 처럼 Jenkins Credential 인 값은 자동 마스킹되지만 이 비밀번호는
                    # docker inspect 로 읽어온 값이라 마스킹 대상이 아니어서 콘솔에 평문으로 남는다.
                    set +x
                    MYSQL_ROOT_PW="$(
                        docker inspect \
                          --format '{{range .Config.Env}}{{println .}}{{end}}' \
                          "$MYSQL_CONTAINER" \
                        | sed -n 's/^MYSQL_ROOT_PASSWORD=//p'
                    )"
                    APP_DB_USER="$(
                        docker inspect \
                          --format '{{range .Config.Env}}{{println .}}{{end}}' \
                          "$MYSQL_CONTAINER" \
                        | sed -n 's/^MYSQL_USER=//p'
                    )"

                    if [ -z "$MYSQL_ROOT_PW" ] || [ -z "$APP_DB_USER" ]; then
                        echo "MySQL 계정 정보를 가져오지 못했습니다."
                        exit 1
                    fi

                    docker exec -e MYSQL_PWD="$MYSQL_ROOT_PW" "$MYSQL_CONTAINER" \
                      mysql -uroot -e "
                        DROP DATABASE IF EXISTS $CI_DB_NAME;
                        CREATE DATABASE $CI_DB_NAME
                          CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
                        GRANT ALL PRIVILEGES ON $CI_DB_NAME.* TO '$APP_DB_USER'@'%';
                        FLUSH PRIVILEGES;"
                    set -x

                    echo "임시 스키마 준비 완료: $CI_DB_NAME"

                    PROD_DB_URL="$(
                        docker inspect \
                          --format '{{range .Config.Env}}{{println .}}{{end}}' \
                          "$SOURCE_BACKEND_CONTAINER" \
                        | sed -n 's/^SPRING_DATASOURCE_URL=//p'
                    )"
                    CI_DB_URL="$(
                        printf '%s' "$PROD_DB_URL" \
                        | sed -E "s#(jdbc:mysql://[^/]+/)[^?]*#\\1$CI_DB_NAME#"
                    )"

                    # 치환이 실패하면 운영 DB를 그대로 쓰게 된다. 조용히 넘어가면 안 되는 지점이다.
                    case "$CI_DB_URL" in
                        *"/$CI_DB_NAME"?*|*"/$CI_DB_NAME") ;;
                        *)
                            echo "임시 스키마 URL 생성에 실패했습니다: $CI_DB_URL"
                            exit 1
                            ;;
                    esac

                    # SPRING_DATASOURCE_URL은 운영 값을 쓰지 않고 위에서 만든 것으로 덮는다.
                    docker inspect \
                      --format '{{range .Config.Env}}{{println .}}{{end}}' \
                      "$SOURCE_BACKEND_CONTAINER" \
                      | grep -E '^(SPRING_PROFILES_ACTIVE|SPRING_DATASOURCE_USERNAME|SPRING_DATASOURCE_PASSWORD)=' \
                      > .ci-backend.env

                    printf '%s\n' "SPRING_DATASOURCE_URL=$CI_DB_URL" >> .ci-backend.env

                    # MQTT_CLIENT_ID는 운영 backend와 달라야 한다. MQTT는 같은 client ID로
                    # 접속하면 브로커가 기존 세션을 끊으므로, 운영값을 그대로 쓰면 임시 컨테이너와
                    # 운영 backend가 살아 있는 동안 서로를 계속 밀어낸다. 실제로 배포마다
                    # 임시 컨테이너가 정리될 때까지 운영 MQTT 연결이 끊겼다 붙기를 반복했다.
                    # 컨테이너 이름을 그대로 쓰면 브랜치·빌드마다 유일하고 로그에서 추적도 쉽다.
                    printf '%s\n' \
                      "JWT_SECRET=$JWT_SECRET" \
                      "JWT_ACCESS_TOKEN_EXPIRATION=$JWT_ACCESS_TOKEN_EXPIRATION" \
                      "JWT_REFRESH_TOKEN_EXPIRATION=$JWT_REFRESH_TOKEN_EXPIRATION" \
                      "MQTT_ENABLED=$MQTT_ENABLED" \
                      "MQTT_BROKER_URL=$MQTT_BROKER_URL" \
                      "MQTT_USERNAME=$MQTT_BACKEND_CREDENTIALS_USR" \
                      "MQTT_PASSWORD=$MQTT_BACKEND_CREDENTIALS_PSW" \
                      "MQTT_CLIENT_ID=$CI_CONTAINER" \
                      "MQTT_TOPIC=$MQTT_TOPIC" \
                      "MQTT_QOS=$MQTT_QOS" \
                      "MQTT_CONNECTION_TIMEOUT_SECONDS=$MQTT_CONNECTION_TIMEOUT_SECONDS" \
                      "MQTT_KEEP_ALIVE_SECONDS=$MQTT_KEEP_ALIVE_SECONDS" \
                      "MQTT_RECOVERY_INTERVAL_MS=$MQTT_RECOVERY_INTERVAL_MS" \
                      >> .ci-backend.env

                    ENV_COUNT="$(wc -l < .ci-backend.env)"

                    if [ "$ENV_COUNT" -lt 17 ]; then
                        echo "임시 컨테이너 실행에 필요한 환경변수를 모두 가져오지 못했습니다."
                        rm -f .ci-backend.env
                        exit 1
                    fi

                    chmod 600 .ci-backend.env
                    echo "임시 컨테이너용 환경 설정 준비 완료"
                '''
            }
        }

        stage('Temporary Container Run') {
            steps {
                sh '''
                    set -eux

                    docker rm -f "$CI_CONTAINER" >/dev/null 2>&1 || true

                    docker run -d \
                      --name "$CI_CONTAINER" \
                      --network "$DOCKER_NETWORK" \
                      --env-file .ci-backend.env \
                      -p "127.0.0.1::8080" \
                      "$CI_IMAGE"

                    docker ps \
                      --filter "name=^/${CI_CONTAINER}$" \
                      --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
                '''
            }
        }

        stage('Temporary Health Check') {
            steps {
                sh '''
                    set +x
                    set -u

                    MAX_ATTEMPTS=30
                    ATTEMPT=1

                    while [ "$ATTEMPT" -le "$MAX_ATTEMPTS" ]; do
                        RUNNING="$(
                            docker inspect \
                              --format '{{.State.Running}}' \
                              "$CI_CONTAINER" 2>/dev/null
                        )"

                        if [ "$RUNNING" != "true" ]; then
                            echo "임시 백엔드 컨테이너가 실행 중이 아닙니다."
                            docker logs --tail 200 "$CI_CONTAINER" || true
                            exit 1
                        fi

                        if docker exec "$CI_CONTAINER" \
                            curl --fail --silent \
                            http://127.0.0.1:8080/actuator/health \
                            >/dev/null 2>&1; then

                            echo "임시 백엔드 Health Check 성공"
                            echo "확인 주소: http://$(
                                docker port "$CI_CONTAINER" 8080 | head -1
                            )/actuator/health"

                            docker exec "$CI_CONTAINER" \
                                curl --fail --silent \
                                http://127.0.0.1:8080/actuator/health

                            exit 0
                        fi

                        echo "Health Check 대기 중: ${ATTEMPT}/${MAX_ATTEMPTS}"
                        ATTEMPT=$((ATTEMPT + 1))
                        sleep 5
                    done

                    echo "임시 백엔드 Health Check 실패"
                    docker logs --tail 200 "$CI_CONTAINER" || true
                    exit 1
                '''
            }
        }

        stage('Prepare Release Image') {
            when {
                environment name: 'IS_DEPLOY_TARGET', value: 'true'
            }

            steps {
                script {
                    // 잡을 새로 만들면 BUILD_NUMBER가 1부터 다시 시작하므로
                    // 빌드 번호만으로는 과거 이미지 태그와 겹칠 수 있다.
                    // 커밋 해시를 함께 넣어 태그가 어느 커밋인지 항상 드러나게 한다.
                    def commitShort = sh(
                        script: 'git rev-parse --short=7 HEAD',
                        returnStdout: true
                    ).trim()

                    env.RELEASE_IMAGE = "potner-backend:build-${env.BUILD_NUMBER}-${commitShort}"
                }

                sh '''
                    set -eux

                    docker tag "$CI_IMAGE" "$RELEASE_IMAGE"

                    docker image inspect \
                      --format 'image={{index .RepoTags 0}} id={{.Id}}' \
                      "$RELEASE_IMAGE"

                    echo "운영 배포 이미지 준비 완료: $RELEASE_IMAGE"
                '''
            }
        }

        stage('Build Inference Image') {
            when {
                environment name: 'IS_DEPLOY_TARGET', value: 'true'
            }

            steps {
                script {
                    // 태그를 빌드 번호가 아니라 디렉터리 tree 해시로 정한다. yolo_cpu_handoff/
                    // 가 바뀌지 않으면 값이 같아서 compose 가 컨테이너를 재생성하지 않고,
                    // 배포마다 모델을 다시 적재하지 않는다.
                    def treeHash = sh(
                        script: 'git rev-parse --short=12 HEAD:yolo_cpu_handoff',
                        returnStdout: true
                    ).trim()

                    env.YOLO_IMAGE = "potner-yolo:tree-${treeHash}"
                }

                sh '''
                    set -eux

                    # 같은 내용의 이미지가 이미 있으면 다시 만들지 않는다. torch 설치가 무거워
                    # 매번 빌드하면 배포가 몇 분씩 길어진다.
                    if docker image inspect "$YOLO_IMAGE" >/dev/null 2>&1; then
                        echo "추론 이미지가 이미 있어 빌드를 생략합니다: $YOLO_IMAGE"
                        exit 0
                    fi

                    docker build \
                      --tag "$YOLO_IMAGE" \
                      yolo_cpu_handoff

                    docker image inspect \
                      --format 'image={{index .RepoTags 0}} id={{.Id}}' \
                      "$YOLO_IMAGE"

                    echo "추론 이미지 준비 완료: $YOLO_IMAGE"
                '''
            }
        }

        stage('Sync Deploy Configs') {
            when {
                environment name: 'IS_DEPLOY_TARGET', value: 'true'
            }

            steps {
                sh '''
                    set -eux

                    MOSQUITTO_CONFIG_DIR="$DEPLOY_DIR/infra/mosquitto/config"

                    test -d "$DEPLOY_DIR"
                    test -d "$MOSQUITTO_CONFIG_DIR"

                    # Git은 파일 권한을 보존하지 않는다.
                    # 브로커가 설정 파일을 그대로 읽으므로 기존 소유자와 권한을 유지해야 한다.
                    MOSQUITTO_UID="$(stat -c %u "$MOSQUITTO_CONFIG_DIR")"
                    MOSQUITTO_GID="$(stat -c %g "$MOSQUITTO_CONFIG_DIR")"

                    CONFIG_BEFORE="$(
                        cat "$MOSQUITTO_CONFIG_DIR/mosquitto.conf" \
                            "$MOSQUITTO_CONFIG_DIR/acl" 2>/dev/null |
                            md5sum | cut -d" " -f1
                    )"

                    install -m 0644 \
                      compose.yml compose.deploy.yml \
                      "$DEPLOY_DIR"/

                    install -o "$MOSQUITTO_UID" -g "$MOSQUITTO_GID" -m 0644 \
                      infra/mosquitto/config/mosquitto.conf \
                      "$MOSQUITTO_CONFIG_DIR"/

                    install -o "$MOSQUITTO_UID" -g "$MOSQUITTO_GID" -m 0600 \
                      infra/mosquitto/config/acl \
                      "$MOSQUITTO_CONFIG_DIR"/

                    install -m 0644 \
                      infra/nginx/conf.d/default.conf \
                      "$DEPLOY_DIR"/infra/nginx/conf.d/

                    install -m 0755 \
                      infra/scripts/*.sh \
                      "$DEPLOY_DIR"/infra/scripts/

                    echo "배포 디렉토리 동기화 완료: $DEPLOY_DIR"

                    CONFIG_AFTER="$(
                        cat "$MOSQUITTO_CONFIG_DIR/mosquitto.conf" \
                            "$MOSQUITTO_CONFIG_DIR/acl" |
                            md5sum | cut -d" " -f1
                    )"

                    if [ "$CONFIG_BEFORE" = "$CONFIG_AFTER" ]; then
                        echo "Mosquitto 설정 변경이 없어 재시작을 생략합니다."
                        exit 0
                    fi

                    MOSQUITTO_CONTAINER="$(
                        docker ps \
                          --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" \
                          --filter "label=com.docker.compose.service=mosquitto" \
                          --format '{{.Names}}' |
                            head -1
                    )"

                    if [ -z "$MOSQUITTO_CONTAINER" ]; then
                        echo "Mosquitto 컨테이너를 찾지 못했습니다."
                        exit 1
                    fi

                    echo "Mosquitto 설정이 변경되어 재시작합니다: $MOSQUITTO_CONTAINER"
                    docker restart "$MOSQUITTO_CONTAINER"
                '''
            }
        }

        stage('Sync Firebase Credentials') {
            when {
                environment name: 'IS_DEPLOY_TARGET', value: 'true'
            }

            steps {
                script {
                    // 경로가 비어 있으면 애플리케이션이 no-op 발송기를 고른다. 기본값을 그렇게
                    // 두는 이유는, 자격증명을 아직 등록하지 않았다는 이유로 배포가 막히면 안 되기
                    // 때문이다. 푸시만 나가지 않고 나머지는 그대로 동작한다.
                    env.FIREBASE_CREDENTIALS_PATH = ''

                    try {
                        withCredentials([file(
                                credentialsId: env.FIREBASE_CREDENTIALS_ID,
                                variable: 'FIREBASE_KEY_FILE'
                        )]) {
                            sh '''
                                set -eu
                                # 키 내용이 콘솔에 남지 않도록 추적을 끈다.
                                # Jenkins sh 스텝은 기본이 `#!/bin/sh -xe` 라 set -eu 만으로는 -x 가 살아 있다.
                                set +x

                                test -s "$FIREBASE_KEY_FILE"

                                # compose 가 자기 라벨이 없는 볼륨을 만나면 경고를 낸다. 먼저 만들면서
                                # 라벨을 붙여 두면 배포 로그가 깨끗하고, 이미 있으면 아무 일도 하지 않는다.
                                docker volume create \
                                  --label com.docker.compose.project="$COMPOSE_PROJECT_NAME" \
                                  --label com.docker.compose.volume=firebase-credentials \
                                  "$FIREBASE_CREDENTIALS_VOLUME" >/dev/null

                                EXPECTED_BYTES="$(wc -c < "$FIREBASE_KEY_FILE" | tr -d ' ')"

                                # 호스트 경로를 쓰지 않고 표준 입력으로 넣는다. Jenkins 는 컨테이너
                                # 안에서 돌아 키 파일이 호스트에 없고, 새 bind mount 를 추가하려면
                                # Jenkins 컨테이너를 재생성해야 한다.
                                #
                                # 잘린 파일이 올라가도 애플리케이션은 조용히 no-op 으로 떨어질 뿐이라
                                # 배포 로그만 보고는 알 수 없다. 같은 컨테이너 안에서 크기를 대조해
                                # 여기서 실패시킨다. 내용은 출력하지 않는다.
                                #
                                # 소유자를 애플리케이션 계정으로 바꾸는 것이 핵심이다. 이 컨테이너는
                                # root 로 돌기 때문에 umask 077 로 만든 파일이 root 소유 0600 이 되고,
                                # backend 는 비루트 계정으로 돌아 읽지 못한다. 사진 볼륨은 이미지가
                                # 마운트 지점을 미리 chown 해 두어 Docker 가 그 소유권을 빈 볼륨에
                                # 복사하지만, 이 볼륨은 파이프라인이 바깥에서 직접 채우므로 그 경로를
                                # 타지 않는다. 0600 은 유지해 다른 계정에는 계속 닫아 둔다.
                                docker run --rm -i \
                                  -v "$FIREBASE_CREDENTIALS_VOLUME":/secrets \
                                  -e TARGET="$FIREBASE_CREDENTIALS_FILE_NAME" \
                                  -e EXPECTED_BYTES="$EXPECTED_BYTES" \
                                  -e APP_UID="$BACKEND_APP_UID" \
                                  alpine:3.20 \
                                  sh -c 'umask 077 && cat > "/secrets/$TARGET" && chown "$APP_UID:$APP_UID" "/secrets/$TARGET" && [ "$(wc -c < "/secrets/$TARGET")" -eq "$EXPECTED_BYTES" ]' \
                                  < "$FIREBASE_KEY_FILE"

                                # 애플리케이션 계정으로 실제로 읽히는지, 그리고 서비스 계정 키가 맞는지
                                # 확인한다. 둘 중 하나라도 어긋나면 배포는 그대로 성공하고 기동 로그에만
                                # ERROR 가 남아 조용히 no-op 이 된다. 여기서 걸러 경고로 드러낸다.
                                #
                                # 종류 확인이 필요한 이유는 Firebase 가 이름이 비슷한 파일을 두 개
                                # 주기 때문이다. google-services.json 은 앱 클라이언트 설정이라
                                # APK 에 들어가고 저장소에도 커밋돼 있는데, 서버 인증에는 쓸 수 없다.
                                # 실제로 그 파일이 Credential 에 올라가 배포가 성공한 채 푸시만
                                # 꺼져 있던 적이 있다. 크기 대조로는 잡히지 않는다.
                                # grep -q 는 일치 여부만 돌려주고 내용을 출력하지 않는다.
                                docker run --rm \
                                  -v "$FIREBASE_CREDENTIALS_VOLUME":/secrets:ro \
                                  --user "$BACKEND_APP_UID:$BACKEND_APP_UID" \
                                  -e TARGET="$FIREBASE_CREDENTIALS_FILE_NAME" \
                                  alpine:3.20 \
                                  sh -c 'test -r "/secrets/$TARGET" && grep -q service_account "/secrets/$TARGET"'
                            '''
                        }

                        env.FIREBASE_CREDENTIALS_PATH =
                                "${env.FIREBASE_CREDENTIALS_MOUNT}/${env.FIREBASE_CREDENTIALS_FILE_NAME}"
                        echo "Firebase 자격증명을 반영했습니다: ${env.FIREBASE_CREDENTIALS_PATH}"
                    } catch (Exception e) {
                        // 자격증명 미등록과 반영 실패를 구분하지 않는다. 어느 쪽이든 배포를 막지
                        // 않는 것이 맞고, 실제로 어느 발송기가 선택됐는지는 기동 로그가 말해 준다.
                        // "Firebase push sender initialized" 또는 "runs as a no-op" 을 확인한다.
                        echo "Firebase 자격증명을 반영하지 못해 푸시를 no-op 으로 둡니다: ${e.message}"
                    }
                }
            }
        }

        stage('Deploy Production Backend') {
            when {
                environment name: 'IS_DEPLOY_TARGET', value: 'true'
            }

            steps {
                script {
                    // 배포 명령은 하나다. LLM 키가 있으면 withCredentials 안에서 실행해
                    // Jenkins 가 콘솔에서 값을 가려 주도록 한다.
                    def runDeploy = {
                        sh '''
                            set -eu

                            test -f "$DEPLOY_DIR/compose.yml"
                            test -f "$DEPLOY_DIR/compose.deploy.yml"
                            test -x "$DEPLOY_DIR/infra/scripts/deploy-backend.sh"

                            POTNER_DEPLOY_DIR="$DEPLOY_DIR" \
                            MQTT_USERNAME="$MQTT_BACKEND_CREDENTIALS_USR" \
                            MQTT_PASSWORD="$MQTT_BACKEND_CREDENTIALS_PSW" \
                            FIREBASE_CREDENTIALS_PATH="${FIREBASE_CREDENTIALS_PATH:-}" \
                            LLM_API_KEY="${LLM_API_KEY:-}" \
                            YOLO_IMAGE="$YOLO_IMAGE" \
                            "$DEPLOY_DIR/infra/scripts/deploy-backend.sh" "$RELEASE_IMAGE"
                        '''
                    }

                    // 키가 아직 등록되지 않았다는 이유로 배포가 막히면 안 된다. 없으면 비운 채로
                    // 진행하고 일기 생성만 no-op 이 된다. Firebase 자격증명과 같은 방침이다.
                    //
                    // 바인딩 직후에 깃발을 세워 "자격증명 없음"과 "배포 실패"를 가른다.
                    // 구분하지 않으면 배포가 실패했을 때 키 없이 한 번 더 배포하게 된다.
                    boolean deployStarted = false
                    try {
                        withCredentials([string(
                                credentialsId: env.LLM_CREDENTIALS_ID,
                                variable: 'LLM_API_KEY'
                        )]) {
                            deployStarted = true
                            runDeploy()
                        }
                    } catch (Exception e) {
                        if (deployStarted) {
                            throw e
                        }
                        echo "LLM 키를 찾지 못해 일기 생성을 no-op 으로 둡니다: ${e.message}"
                        runDeploy()
                    }
                }
            }
        }

    }

    post {
        always {
            junit allowEmptyResults: true,
                  testResults: 'backend/build/test-results/*/*.xml'

            archiveArtifacts allowEmptyArchive: true,
                             artifacts: 'backend/build/reports/tests/**'
        }

        success {
            script {
                reportGitlabStatus('success')
            }

            // 배포 스테이지는 IS_DEPLOY_TARGET 이 true 일 때만 실행된다. 그렇지 않은 브랜치에서
            // "운영 배포 성공"으로 찍으면 배포되지 않았는데 배포된 것으로 오해하게 된다.
            echo env.IS_DEPLOY_TARGET == 'true'
                    ? 'Potner Backend CI/CD 및 운영 배포 성공'
                    : "Potner Backend CI 성공 - 배포 대상 브랜치가 아니므로 배포는 건너뜀 (branch=${env.BRANCH_NAME ?: 'configured SCM branch'})"
        }

        failure {
            script {
                reportGitlabStatus('failed')
            }

            echo 'Potner Backend CI/CD 실패 - Console Output 확인 필요'
        }

        unstable {
            script {
                reportGitlabStatus('failed')
            }

            echo 'Potner Backend CI/CD 불안정 - 테스트 결과 확인 필요'
        }

        aborted {
            script {
                reportGitlabStatus('canceled')
            }

            echo 'Potner Backend CI/CD 중단됨'
        }

        cleanup {
            sh '''
                set +e

                docker rm -f "$CI_CONTAINER" >/dev/null 2>&1
                docker image rm -f "$CI_IMAGE" >/dev/null 2>&1
                rm -f .ci-backend.env

                # 임시 스키마를 남기지 않는다. 다음 빌드가 어차피 DROP 하지만 브랜치가 삭제되면
                # 그 스키마는 아무도 지우지 않아 계속 쌓인다.
                MYSQL_CONTAINER="$(
                    docker ps \
                      --filter "label=com.docker.compose.service=mysql" \
                      --format '{{.Names}}' \
                    | head -1
                )"

                if [ -n "$MYSQL_CONTAINER" ]; then
                    # 준비 스테이지와 같은 이유로 이 구간만 추적을 끈다. 비밀번호가 콘솔에 남는다.
                    set +x
                    MYSQL_ROOT_PW="$(
                        docker inspect \
                          --format '{{range .Config.Env}}{{println .}}{{end}}' \
                          "$MYSQL_CONTAINER" \
                        | sed -n 's/^MYSQL_ROOT_PASSWORD=//p'
                    )"

                    if [ -n "$MYSQL_ROOT_PW" ]; then
                        docker exec -e MYSQL_PWD="$MYSQL_ROOT_PW" "$MYSQL_CONTAINER" \
                          mysql -uroot -e "DROP DATABASE IF EXISTS $CI_DB_NAME;" >/dev/null 2>&1
                    fi
                    set -x
                fi

                echo "임시 컨테이너, 임시 이미지, 임시 스키마 및 환경 파일 정리 완료"
                exit 0
            '''

            deleteDir()
        }
    }
}
