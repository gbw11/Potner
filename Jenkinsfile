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
        timeout(time: 40, unit: 'MINUTES')
        disableConcurrentBuilds()
        gitLabConnection('SSAFY GitLab')
    }

    environment {
        // Flutter 툴체인은 Jenkins에 설치하지 않고 컨테이너로만 사용한다.
        // 태그를 고정하지 않으면 이미지가 갱신될 때 빌드 결과가 조용히 달라진다.
        FLUTTER_IMAGE = 'ghcr.io/cirruslabs/flutter:3.44.0'

        // 스테이지마다 컨테이너가 새로 뜨기 때문에 pub 캐시를 컨테이너 안에 두면
        // 다음 스테이지에서 의존성을 찾지 못한다. 캐시는 반드시 볼륨에 둔다.
        PUB_CACHE_VOLUME = 'potner-flutter-pub-cache'
        GRADLE_CACHE_VOLUME = 'potner-flutter-gradle-cache'

        // Android 빌드는 이미지에 없는 NDK를 내려받는다. 컨테이너 안에 두면
        // 매 빌드마다 수 GB를 다시 받게 되므로 SDK 디렉토리도 볼륨에 둔다.
        // 이름 있는 볼륨은 처음 사용할 때 이미지의 내용으로 초기화된다.
        ANDROID_SDK_VOLUME = 'potner-flutter-android-sdk'

        // Jenkins 워크스페이스는 이 볼륨 안에 있다.
        // 빌드 컨테이너에 docker 소켓까지 넘기지 않도록 필요한 볼륨만 연결한다.
        JENKINS_HOME_VOLUME = 'potner-jenkins-home'

        // Multibranch 잡은 BRANCH_NAME이 채워지고, 클래식 단일 브랜치 잡은 비어 있다.
        // 릴리스 산출물은 App-master에서만 만든다.
        IS_RELEASE_TARGET = "${(env.BRANCH_NAME == null || env.BRANCH_NAME == 'App-master') ? 'true' : 'false'}"

        // debug APK가 볼 서버 주소다. 주지 않으면 lib/core/config/app_config.dart의 기본값
        // http://10.0.2.2:8080 이 박히는데, 그것은 안드로이드 에뮬레이터가 호스트를 가리키는
        // 주소라 실기에 설치하면 아무 데도 접속하지 못한다.
        //
        // 운영 nginx는 TLS 없이 80 포트로 서빙한다. debug 소스셋에만 usesCleartextTraffic이
        // 있으므로 이 주소는 debug 빌드에서만 쓸 수 있다.
        API_BASE_URL = 'http://i15e104.p.ssafy.io/api/v1'
    }

    stages {
        stage('Checkout Info') {
            steps {
                script {
                    reportGitlabStatus('running')
                }

                echo "Branch: ${env.BRANCH_NAME ?: 'configured SCM branch'}"
                echo "Workspace: ${env.WORKSPACE}"
                echo "Release 대상: ${env.IS_RELEASE_TARGET}"

                sh '''
                    set -eux

                    git log -1 --oneline
                    test -f pubspec.yaml
                    test -d lib
                    test -d test

                    docker run --rm "$FLUTTER_IMAGE" flutter --version
                '''
            }
        }

        stage('Dependencies') {
            steps {
                sh '''
                    set -eux

                    docker run --rm \
                      -v "$JENKINS_HOME_VOLUME":/var/jenkins_home \
                      -v "$PUB_CACHE_VOLUME":/pub-cache \
                      -w "$WORKSPACE" \
                      -e PUB_CACHE=/pub-cache \
                      "$FLUTTER_IMAGE" \
                      flutter pub get
                '''
            }
        }

        stage('Analyze') {
            steps {
                sh '''
                    set -eux

                    docker run --rm \
                      -v "$JENKINS_HOME_VOLUME":/var/jenkins_home \
                      -v "$PUB_CACHE_VOLUME":/pub-cache \
                      -w "$WORKSPACE" \
                      -e PUB_CACHE=/pub-cache \
                      "$FLUTTER_IMAGE" \
                      flutter analyze
                '''
            }
        }

        stage('Test') {
            steps {
                sh '''
                    set -eux

                    docker run --rm \
                      -v "$JENKINS_HOME_VOLUME":/var/jenkins_home \
                      -v "$PUB_CACHE_VOLUME":/pub-cache \
                      -w "$WORKSPACE" \
                      -e PUB_CACHE=/pub-cache \
                      "$FLUTTER_IMAGE" \
                      flutter test
                '''
            }
        }

        stage('Android Debug Build') {
            steps {
                // Gradle 캐시 볼륨은 프로세스 간 동시 사용이 안 된다. 브랜치 빌드가
                // 병렬로 돌면 journal-1.lock 경합으로 60초 대기 후 죽으므로, 공유
                // 볼륨의 잠금 파일에 flock 을 걸어 Gradle 단계만 직렬화한다.
                // 대기 상한(-w)은 파이프라인 타임아웃(40분)보다 짧게 둔다.
                sh '''
                    set -eux

                    docker run --rm \
                      -v "$JENKINS_HOME_VOLUME":/var/jenkins_home \
                      -v "$PUB_CACHE_VOLUME":/pub-cache \
                      -v "$GRADLE_CACHE_VOLUME":/root/.gradle \
                      -v "$ANDROID_SDK_VOLUME":/opt/android-sdk-linux \
                      -w "$WORKSPACE" \
                      -e PUB_CACHE=/pub-cache \
                      "$FLUTTER_IMAGE" \
                      flock -w 1500 /var/jenkins_home/potner-gradle-ci.lock \
                      flutter build apk --debug --dart-define=API_BASE_URL="$API_BASE_URL"

                    ls -la build/app/outputs/flutter-apk/
                '''
            }

            post {
                // 실기 확인용으로 받아 갈 수 있게 보관한다. 빌드만 하고 버리면 APK가 컨테이너와
                // 함께 사라져, 확인하려는 사람이 Flutter 툴체인을 직접 갖춰야 한다.
                //
                // 모든 App-* 브랜치에서 보관한다. 확인이 필요한 시점은 대개 머지 전이다.
                success {
                    archiveArtifacts allowEmptyArchive: false,
                                     artifacts: 'build/app/outputs/flutter-apk/app-debug.apk',
                                     fingerprint: true
                }
            }
        }

        stage('Android Release Bundle') {
            when {
                environment name: 'IS_RELEASE_TARGET', value: 'true'
            }

            steps {
                sh '''
                    set -eux

                    docker run --rm \
                      -v "$JENKINS_HOME_VOLUME":/var/jenkins_home \
                      -v "$PUB_CACHE_VOLUME":/pub-cache \
                      -v "$GRADLE_CACHE_VOLUME":/root/.gradle \
                      -v "$ANDROID_SDK_VOLUME":/opt/android-sdk-linux \
                      -w "$WORKSPACE" \
                      -e PUB_CACHE=/pub-cache \
                      "$FLUTTER_IMAGE" \
                      flock -w 1500 /var/jenkins_home/potner-gradle-ci.lock \
                      flutter build appbundle --release

                    ls -la build/app/outputs/bundle/release/
                '''

                archiveArtifacts allowEmptyArchive: false,
                                 artifacts: 'build/app/outputs/bundle/release/*.aab',
                                 fingerprint: true

                echo '''릴리스 번들이 보관되었습니다.
현재 android/app/build.gradle.kts의 release 서명이 debug 키로 설정되어 있어
이 번들은 내부 확인용입니다. 스토어 업로드 전에 서명 키 설정이 필요합니다.'''
            }
        }
    }

    post {
        success {
            script {
                reportGitlabStatus('success')
            }

            echo 'Potner App CI 성공'
        }

        failure {
            script {
                reportGitlabStatus('failed')
            }

            echo 'Potner App CI 실패 - Console Output 확인 필요'
        }

        unstable {
            script {
                reportGitlabStatus('failed')
            }

            echo 'Potner App CI 불안정 - 테스트 결과 확인 필요'
        }

        aborted {
            script {
                reportGitlabStatus('canceled')
            }

            echo 'Potner App CI 중단됨'
        }

        cleanup {
            deleteDir()
        }
    }
}
