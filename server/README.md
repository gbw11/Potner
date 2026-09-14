# Potner 서버 구성

Potner의 초기 서버 구성은 Spring Boot backend, MySQL, Mosquitto, Nginx를 Docker Compose v2로 실행하는 것을 목표로 합니다. 이 단계는 로컬 Git 저장소의 코드와 설정 파일만 구성하며 EC2, 방화벽, SSH, 기존 서비스에는 접속하거나 변경하지 않습니다.

이 문서는 인프라 구성과 배포 절차를 다룹니다. **애플리케이션이 어떤 기능을 어떻게 구현했는지는
[docs/BACKEND.md](docs/BACKEND.md)** 를 보십시오. 도메인 모델, 판정 계산식, API 목록, 설정 프로퍼티,
반복해서 부딪힌 제약과 그 결정 이유가 정리되어 있습니다.

### 문서 목록

| 문서 | 무엇을 다루는가 | 누가 보는가 |
| --- | --- | --- |
| 이 README | 인프라 구성, 배포 절차, 운영 명령 | 서버·인프라 |
| [docs/BACKEND.md](docs/BACKEND.md) | 도메인 모델, 판정 계산식, API 목록, 설정 | 서버·앱 |
| [docs/DEVICE-MQTT.md](docs/DEVICE-MQTT.md) | 장치 공통 MQTT 계약과 설계 근거 | 장치 전체 |
| [docs/DEVICE-RASPBERRY.md](docs/DEVICE-RASPBERRY.md) | 라즈베리가 할 일, 현재 상태, 남은 작업 | 라즈베리 담당 |
| [docs/DEVICE-JETSON.md](docs/DEVICE-JETSON.md) | 젯슨이 할 일, 현재 상태, 남은 작업 | 젯슨 담당 |

문서끼리 어긋나면 **코드가 기준**입니다.

## 아키텍처

```text
외부 HTTP :80 (POTNER_HTTP_PORT)
        |
      Nginx :80
        |
      backend:8080 (Spring Boot)
        |
      mysql:3306 (MySQL)

장비 MQTT :1883 (MQTT_PORT)
        |
      Mosquitto :1883
```

모든 컨테이너는 `potner-network`에 연결됩니다. Nginx만 HTTP를 외부에 공개하고, Mosquitto는 장비 연결을 위해 MQTT 포트를 외부에 공개합니다. MySQL 3306과 backend 8080은 호스트에 공개하지 않습니다.

## 디렉터리 구조

```text
.
├── backend/
│   ├── src/main/java/com/potner/
│   │   ├── PotnerApplication.java
│   │   └── health/{HealthController.java,HealthResponse.java}
│   ├── src/main/resources/{application*.yml,db/migration/}
│   ├── src/test/{java,resources}/
│   ├── gradle/wrapper/
│   ├── build.gradle
│   ├── Dockerfile
│   └── .dockerignore
├── infra/nginx/conf.d/default.conf
├── infra/mosquitto/config/{mosquitto.conf,acl}
├── infra/scripts/{preflight.sh,health-check.sh}
├── compose.yml
├── .env.example
├── .gitignore
└── .gitattributes
```

## 기술 버전

- Java 21 LTS, Eclipse Temurin/Adoptium
- Spring Boot 4.1.0
- Gradle Wrapper 8.14.5
- MySQL `8.4.10`
- Nginx `1.27.5-alpine`: 공식 Alpine 이미지이며 `latest`를 피하고 재현 가능한 버전을 고정했습니다.
- Eclipse Mosquitto `2.0.22`: 공식 2.x 이미지이며 `latest`를 피하고 재현 가능한 버전을 고정했습니다.
- Docker Engine 및 Docker Compose v2

Spring Boot BOM이 관리하는 의존성에는 개별 버전을 지정하지 않았습니다. Lombok, MapStruct, Spring Security, JWT, Firebase, S3, Redis, Kafka, MQTT Java Client는 포함하지 않았습니다.

## 서비스별 역할과 주소

| 서비스 | 내부 주소 | 외부 공개 | 역할 |
| --- | --- | --- | --- |
| mysql | `mysql:3306` | 없음 | 애플리케이션용 MySQL |
| mosquitto | `mosquitto:1883` | `${MQTT_PORT:-1883}:1883` | 인증된 MQTT 브로커 |
| backend | `backend:8080` | 없음 | Spring Boot API 및 Actuator |
| nginx | `nginx:80` | `${POTNER_HTTP_PORT:-80}:80` | HTTP reverse proxy 및 성장 사진 정적 서빙 |

Health API는 `GET /api/v1/health`에서 `{"service":"potner","status":"UP"}`를 반환합니다. Actuator는 `health,info`만 외부 노출하며 `/actuator/env`, `/actuator/configprops`, `/actuator/beans`는 Nginx에서 차단합니다.

## 성장 사진 저장

라즈베리가 올린 사진은 backend가 디스크에 쓰고 Nginx가 정적으로 서빙합니다. 사진은 backend를 거치지 않고 나갑니다.

```text
장치 ──POST /api/v1/device/photos──▶ backend ──▶ potner-photo-data 볼륨
                                                        │
앱 ◀── GET {PHOTO_BASE_URL}/... ◀── Nginx (읽기 전용) ◀──┘
```

| | |
| --- | --- |
| 볼륨 | `potner-photo-data` (named volume) |
| backend 마운트 | `/var/potner/photos` 읽기·쓰기. `PHOTO_ROOT`와 같아야 합니다 |
| nginx 마운트 | `/var/potner/photos` **읽기 전용**. 쓰기는 backend만 합니다 |
| 공개 경로 | `/media/{plantId}/{photoId}/{original\|playback\|thumbnail}.jpg` |
| 응답 URL | **상대 경로**. 앱이 자기 API base URL에 붙입니다 |

**배포 디렉터리 안이 아니라 named volume입니다.** 배포 파이프라인이 배포 디렉터리를 매번 GitLab 최신 내용으로 교체하므로, 그 안에 두면 배포마다 사진이 사라집니다.

**배포 시 설정할 환경 변수가 없습니다.** 사진은 API와 같은 origin의 `/media`로 나가므로 서버가 상대 경로(`/media/...`)를 내려주고 앱이 자기 API base URL에 붙입니다. 서버가 도메인을 알 필요가 없어 도메인 변경이나 HTTPS 전환에도 설정을 건드리지 않습니다.

사진을 다른 origin(CDN, 오브젝트 스토리지)으로 옮길 때만 `.env`에 `PHOTO_BASE_URL`을 절대 URL로 넣으면 됩니다.

### 이 경로에는 인증이 걸리지 않습니다

Nginx가 정적으로 서빙하므로 URL을 아는 사람은 누구나 볼 수 있습니다. 경로에 들어가는 UUID 두 개(식물, 사진)가 추측을 막는 유일한 방어선입니다. 그래서 Nginx는 backend가 쓰는 레이아웃과 정확히 일치하는 요청만 통과시키고 나머지는 404로 막습니다. 디렉터리 목록도, 예상 밖의 파일도 노출되지 않습니다.

URL이 한 번 유출되면(공유·브라우저 이력·로그) 영구 접근이 가능합니다. 더 조여야 하면 Nginx `auth_request`로 backend에 인증을 위임하는 방향이 있습니다.

### 컨테이너 사용자와 볼륨 소유권

backend는 non-root(`10001:10001`)로 실행됩니다. Dockerfile이 `/var/potner/photos`를 미리 만들고 소유자를 맞추는 이유가 이것입니다. Docker는 빈 named volume을 처음 마운트할 때 이미지 경로의 소유·권한을 복사하므로, 그 단계가 없으면 볼륨이 root 소유로 만들어져 **업로드가 권한 오류로 실패**합니다.

## 비밀값 관리

실제 배포에서는 루트 `.env`를 직접 생성하고 Git에 추가하지 않습니다. `.env.example`의 값은 예시용 placeholder이며 실제 비밀번호가 아닙니다. `infra/mosquitto/config/password.txt`도 생성하지 않고 Git에서 제외합니다.

Mosquitto는 `allow_anonymous false`와 `password_file`을 사용하므로 `password.txt`가 없으면 시작할 수 없습니다. EC2에서 실제 운영 비밀번호를 준비한 뒤 다음처럼 계정을 생성합니다. 실제 비밀번호를 명령 기록이나 저장소에 남기지 않도록 주의하십시오.

```bash
docker run --rm -it \
  --entrypoint mosquitto_passwd \
  -v "$PWD/infra/mosquitto/config:/mosquitto/config" \
  eclipse-mosquitto:2.0.22 \
  -c /mosquitto/config/password.txt potner-server

docker run --rm -it \
  --entrypoint mosquitto_passwd \
  -v "$PWD/infra/mosquitto/config:/mosquitto/config" \
  eclipse-mosquitto:2.0.22 \
  /mosquitto/config/password.txt raspberry-01

docker run --rm -it \
  --entrypoint mosquitto_passwd \
  -v "$PWD/infra/mosquitto/config:/mosquitto/config" \
  eclipse-mosquitto:2.0.22 \
  /mosquitto/config/password.txt jetson-01
```

ACL의 Topic 명세는 초기 초안입니다. 실제 MQTT 기능 구현 전에 재검토하고, 운영 전에 최소 권한 원칙을 적용해야 합니다.

## 로컬 빌드 및 테스트

Windows:

```powershell
cd backend
.\gradlew.bat clean test
.\gradlew.bat clean bootJar
```

POSIX/WSL/Git Bash:

```bash
cd backend
./gradlew clean test
./gradlew clean bootJar
```

테스트는 MySQL Testcontainers를 사용하며 H2는 사용하지 않습니다. 따라서 테스트에는 Docker Engine과 이미지 pull 권한이 필요합니다. 전체 테스트는 Docker 이미지 빌드 이전에 별도 실행합니다. Dockerfile은 컨텍스트를 단순하게 유지하고 `bootJar`만 빌드하며, 테스트 결과와 이미지 빌드 결과를 분리해 원인 파악이 쉽도록 하기 위한 절차입니다.

Docker 이미지 빌드는 저장소 루트에서 수행합니다.

```bash
docker compose --env-file .env.example config
docker compose --env-file .env.example build backend
```

Dockerfile은 Temurin 21 JDK builder에서 기존 Gradle Wrapper로 `clean bootJar`를 실행하고, Temurin 21 JRE runtime에는 executable Boot JAR만 복사합니다. runtime은 전용 non-root 사용자로 실행하고 컨테이너 메모리 제한을 인식하도록 JVM 옵션을 설정합니다. healthcheck에 필요한 `curl`만 runtime 이미지에 추가합니다. Windows checkout의 CRLF 문제는 builder에서 `gradlew`의 줄바꿈을 LF로 정규화해 방지합니다.

## EC2 배포 순서

EC2에서 다음 순서를 사용합니다. 아래 명령 자체는 이 작업에서 실행하지 않았습니다.

```bash
cd /home/ubuntu/potner

cp .env.example .env
chmod 600 .env
# .env의 placeholder를 실제 운영값으로 교체

bash infra/scripts/preflight.sh
docker compose --env-file .env config

# password.txt를 생성한 뒤 순차 실행
docker compose --env-file .env up -d mysql
docker compose --env-file .env up -d mosquitto
docker compose --env-file .env up -d --build backend
docker compose --env-file .env up -d nginx

docker compose --env-file .env ps
bash infra/scripts/health-check.sh
```

`preflight.sh`는 현재 사용자, OS, Docker/Compose v2, Docker Engine, 메모리, 디스크, 22/80/443/8989/1883/3306/8080 포트, `.env`, `compose.yml`, Mosquitto password 파일, 필수 환경 변수, `gerrit.service` 조회 가능 여부를 검사합니다. 설치·방화벽·systemd·파일 권한·기존 서비스에는 변경을 가하지 않습니다.

## 운영 명령

```bash
# 상태 및 로그
docker compose --env-file .env ps
docker compose --env-file .env logs -f nginx
docker compose --env-file .env logs -f backend
docker compose --env-file .env logs -f mysql
docker compose --env-file .env logs -f mosquitto

# 서비스 재시작
docker compose --env-file .env restart backend nginx

# 일반적인 서비스 종료: named volume은 보존
docker compose --env-file .env down
```

`docker compose down -v`는 일반 배포 절차에서 사용하지 않습니다. 이 명령은 `mysql-data`, `mosquitto-data`, `mosquitto-log`, **`photo-data`**를 삭제해 MySQL, Mosquitto 데이터와 **업로드된 사진 전부**를 잃을 수 있습니다. 사진은 DB 행과 달리 다시 만들 수 없습니다. 데이터 보존이 필요한 동안에는 `down`만 사용하십시오.

## 범위 밖 항목과 다음 단계

이번 단계에서는 현재 EC2의 기존 22/443/8989 규칙, Gerrit, UFW, Docker 상태를 변경하지 않습니다. HTTPS와 443 Nginx 설정은 기존 EC2 설정과 인증서 운영을 확인한 뒤 별도 단계에서 적용해야 하므로 아직 작성하지 않았습니다. Jenkinsfile, Jenkins Credential, GitLab Webhook도 배포 파이프라인 요구사항이 확정된 뒤 별도 단계에서 추가합니다.

다음 단계는 다음과 같습니다.

1. EC2에서 `.env`와 Mosquitto `password.txt`를 안전하게 생성하고 preflight를 통과시킵니다.
2. 운영 환경에서 Compose config, Nginx 설정, 이미지 빌드와 순차 기동을 검증합니다.
3. 실제 DB 스키마가 승인되면 Flyway migration을 추가합니다.
4. MQTT Topic 명세와 최소 권한 ACL을 재검토한 뒤 애플리케이션 MQTT 연동을 별도 구현합니다.
5. HTTPS, 인증, CI/CD는 운영 정책과 기존 서비스 충돌을 확인한 뒤 별도 변경으로 진행합니다.
