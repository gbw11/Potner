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

        // Multibranch에서는 브랜치마다 빌드 번호가 따로 매겨진다.
        // 서로 다른 브랜치가 동시에 빌드될 때 임시 이미지와 컨테이너 이름이
        // 겹치지 않도록 브랜치 이름을 함께 넣는다.
        BRANCH_SLUG = "${(env.BRANCH_NAME ?: 'single').replaceAll('[^A-Za-z0-9]', '-').toLowerCase()}"

        CI_IMAGE = "potner-backend-ci:${BRANCH_SLUG}-${BUILD_NUMBER}"
        CI_CONTAINER = "potner-backend-ci-${BRANCH_SLUG}-${BUILD_NUMBER}"
        DOCKER_NETWORK = 'potner-network'

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

                    docker inspect \
                      --format '{{range .Config.Env}}{{println .}}{{end}}' \
                      "$SOURCE_BACKEND_CONTAINER" \
                      | grep -E '^(SPRING_PROFILES_ACTIVE|SPRING_DATASOURCE_URL|SPRING_DATASOURCE_USERNAME|SPRING_DATASOURCE_PASSWORD)=' \
                      > .ci-backend.env

                    printf '%s\n' \
                      "JWT_SECRET=$JWT_SECRET" \
                      "JWT_ACCESS_TOKEN_EXPIRATION=$JWT_ACCESS_TOKEN_EXPIRATION" \
                      "JWT_REFRESH_TOKEN_EXPIRATION=$JWT_REFRESH_TOKEN_EXPIRATION" \
                      "MQTT_ENABLED=$MQTT_ENABLED" \
                      "MQTT_BROKER_URL=$MQTT_BROKER_URL" \
                      "MQTT_USERNAME=$MQTT_BACKEND_CREDENTIALS_USR" \
                      "MQTT_PASSWORD=$MQTT_BACKEND_CREDENTIALS_PSW" \
                      "MQTT_CLIENT_ID=$MQTT_CLIENT_ID" \
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

        stage('Deploy Production Backend') {
            when {
                environment name: 'IS_DEPLOY_TARGET', value: 'true'
            }

            steps {
                sh '''
                    set -eu

                    test -f "$DEPLOY_DIR/compose.yml"
                    test -f "$DEPLOY_DIR/compose.deploy.yml"
                    test -x "$DEPLOY_DIR/infra/scripts/deploy-backend.sh"

                    POTNER_DEPLOY_DIR="$DEPLOY_DIR" \
                    MQTT_USERNAME="$MQTT_BACKEND_CREDENTIALS_USR" \
                    MQTT_PASSWORD="$MQTT_BACKEND_CREDENTIALS_PSW" \
                    "$DEPLOY_DIR/infra/scripts/deploy-backend.sh" "$RELEASE_IMAGE"
                '''
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

            echo 'Potner Backend CI/CD 및 운영 배포 성공'
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

                echo "임시 컨테이너, 임시 이미지 및 환경 파일 정리 완료"
                exit 0
            '''

            deleteDir()
        }
    }
}
