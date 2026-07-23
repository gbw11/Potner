pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
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

        CI_IMAGE = "potner-backend-ci:${BUILD_NUMBER}"
        CI_CONTAINER = "potner-backend-ci-${BUILD_NUMBER}"
        RELEASE_IMAGE = "potner-backend:build-${BUILD_NUMBER}"
        CI_PORT = '18081'
        DOCKER_NETWORK = 'potner-network'
    }

    stages {
        stage('Checkout Info') {
            steps {
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
                      -p "127.0.0.1:${CI_PORT}:8080" \
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
                            echo "확인 주소: http://127.0.0.1:${CI_PORT}/actuator/health"

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
            steps {
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

        stage('Deploy Production Backend') {
            steps {
                sh '''
                    set -eu

                    test -f compose.deploy.yml
                    test -f infra/scripts/deploy-backend.sh

                    chmod +x infra/scripts/deploy-backend.sh

                    MQTT_USERNAME="$MQTT_BACKEND_CREDENTIALS_USR" \
                    MQTT_PASSWORD="$MQTT_BACKEND_CREDENTIALS_PSW" \
                    ./infra/scripts/deploy-backend.sh "$RELEASE_IMAGE"
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
            echo 'Potner Backend CI/CD 및 운영 배포 성공'
        }

        failure {
            echo 'Potner Backend CI/CD 실패 - Console Output 확인 필요'
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
