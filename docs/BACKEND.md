# Potner 백엔드 기능 명세

이 문서는 Potner 백엔드가 **무엇을 하는지, 왜 그렇게 만들었는지**를 처음 보는 사람이 읽고
이해할 수 있도록 정리한 것입니다. 인프라 구성과 배포 절차는 저장소 루트의 `README.md`를 보세요.

기준 시점: 2026년 7월 26일. 단위 테스트 137개, 통합 테스트 55개.

---

## 1. 제품이 하는 일

화분에 붙은 장치가 센서값을 보내오고, 서버가 그 값을 **식물별 생육 기준**과 비교해
이상하면 알림을 만듭니다.

```
장치(라즈베리파이 / 젯슨)
   │  MQTT 발행
   ▼
Mosquitto 브로커
   │  구독
   ▼
Spring Boot ──▶ sensor_reading 저장
                    │
                    ├─▶ 즉시: 최근 측정값으로 이상 판정 → alert 생성
                    │
                    └─▶ 새벽 배치: 하루 누적 광량 판정 → alert 생성
                                                          │
Flutter 앱 ◀── 조회 API ──────────────────────────────────┘
```

핵심은 **"기준"이 식물마다 다르다**는 점입니다. 같은 바질이라도 생장 단계가 다르면
적정 토양수분이 다르고, 사용자가 기준을 직접 수정할 수도 있습니다.

---

## 2. 기술 구성

| | |
|---|---|
| 언어 / 프레임워크 | Java 21 (Temurin), Spring Boot 4.1.0 |
| DB | MySQL 8.4, Flyway 마이그레이션 (V1~V9) |
| 메시징 | Mosquitto 2.0.22, Spring Integration MQTT (Paho v3) |
| 인증 | 자체 JWT (Access + Refresh) |
| 문서 | SpringDoc OpenAPI (운영에서는 env 플래그로 비활성) |
| 테스트 | JUnit 5, Mockito, MySQL Testcontainers |

빌드는 Gradle이고 테스트는 두 태스크로 나뉩니다.

```bash
./gradlew test              # 단위 테스트 (Docker 불필요)
./gradlew integrationTest   # Testcontainers 사용 (Docker 필요)
```

> **로컬 개발 주의**: `build.gradle`이 테스트 JVM 타임존을 UTC로 고정합니다.
> 이유는 [9절](#9-반복해서-부딪힌-제약과-결정)에 있습니다.

---

## 3. 데이터 모델 지도

```
app_user ──┬── refresh_token
           ├── user_notification_setting   (1:1, 행이 없으면 기본값)
           ├── alert
           ├── robot ──── iot_device
           └── plant ──┬── plant_growth_profile   (1:1, 적용 중인 생육 기준)
                       ├── plant_device_assignment ── robot
                       ├── sensor_reading
                       ├── plant_daily_light
                       └── alert

기준정보(시드 데이터, 사용자와 무관):
plant_category ── plant_species ──┐
plant_life_stage ─────────────────┴── species_growth_requirement
```

### 기준정보와 적용 기준의 관계

이 구조가 이 프로젝트의 중심입니다.

```
species_growth_requirement   "바질 × 발아기의 표준 생육 기준"   ← 시드 데이터, 공용
            │  식물 등록 시 복사(스냅샷)
            ▼
plant_growth_profile         "내 바질에 실제 적용 중인 기준"    ← 사용자가 수정 가능
```

복사해두는 이유는 두 가지입니다.

1. 사용자가 자기 식물에만 맞춤 기준을 쓸 수 있어야 합니다.
2. 나중에 표준 기준이 개정되어도 이미 키우는 식물의 기준이 갑자기 바뀌면 안 됩니다.
   그래서 복사 당시의 `source_revision`을 함께 저장합니다.

생장 단계를 바꾸면(`PATCH /plants/{id}/growth-stage`) 새 단계의 표준 기준을 다시 복사합니다.
`POST .../growth-profile/reset`은 현재 단계의 표준값으로 되돌립니다.

### 지원 식물 6종과 생장 단계

생장 단계는 **종마다 다릅니다.** 임의로 다른 게 아니라 작물 특성 때문입니다.

| 종 | 지원 생장 단계 | 왜 |
|---|---|---|
| 미니해바라기 | 발아기 · 유묘기 · 영양생장기 · 개화기 | |
| 배초향 | 발아기 · 유묘기 · 영양생장기 · 개화기 | |
| 일일초 | 발아기 · 유묘기 · 영양생장기 · 개화기 | |
| 바질 | 발아기 · 유묘기 · 영양생장기 · **재생장기** | 잎을 먹는 허브라 개화 전에 수확하고 다시 키움. 개화기 없음 |
| 칼란디바 | **유묘기** · 영양생장기 · **꽃눈형성기** · 개화기 | 다육이라 삽목 번식(발아기 없음). 단일식물이라 꽃눈형성기가 별도로 필요 |
| 방울토마토 | 발아기 · 유묘기 · 영양생장기 · 개화기 · **결실기** | 열매를 먹음 |

유묘기와 영양생장기만 6종 공통입니다. **앱은 자람 수준 옵션을 하드코딩하면 안 되고**
종을 고른 뒤 `GET /plant-species/{id}/growth-stages`로 받아 채워야 합니다.

---

## 4. 인증

이메일 회원가입 / 로그인 / Access Token 재발급 / 로그아웃.

- 비밀번호는 BCrypt 해시로만 저장합니다.
- **Refresh Token도 원문을 저장하지 않고 해시로 저장**합니다(`refresh_token.token_hash`).
- `JwtAuthenticationFilter`가 **매 요청마다 DB에서 계정 상태를 확인**합니다.
  비용이 있지만, 덕분에 정지·탈퇴가 즉시 반영됩니다(Access Token 만료를 기다리지 않음).
- 비밀번호 정책은 `common/validation/PasswordConstraints` 한 곳에 있고
  회원가입과 비밀번호 변경이 공유합니다.

---

## 5. 센서 수집 (MQTT)

### 토픽과 페이로드

```
potner/device/{device_uid}/sensor/telemetry
{ "messageId": "<UUID>", "deviceId": "raspberry-01", "sensorType": "SOIL_MOISTURE",
  "value": 42.5, "unit": "PERCENT", "measuredAt": "2026-07-26T14:03:11+09:00" }

potner/device/{device_uid}/status/heartbeat
{ "messageId": "<UUID>", "deviceId": "raspberry-01", "sentAt": "2026-07-26T14:03:11+09:00" }
```

`device_uid`가 **네 곳에서 같아야** 합니다: MQTT 토픽 세그먼트, 페이로드 `deviceId`,
`iot_device.device_uid`, Mosquitto 계정명. 토픽과 페이로드가 다르면 메시지를 버립니다.

### 유입 경로

```
device_uid → iot_device → robot → plant_device_assignment(활성) → plant
```

즉 장치는 로봇에 속하고, 로봇이 식물에 배정되어 있어야 측정값이 저장됩니다.
어느 단계든 없으면 경고 로그만 남기고 버립니다.

### 조용히 버려지는 경우

| 조건 | |
|---|---|
| `messageId` 중복 | `sensor_reading.device_message_id`가 **전역 UNIQUE**. QoS 1 재전송 방어 |
| 타입↔단위 불일치 | `TEMPERATURE`=CELSIUS(−40~85), `HUMIDITY`/`SOIL_MOISTURE`=PERCENT(0~100), `ILLUMINANCE`=LUX(≥0) |
| `measuredAt`이 10분 이상 미래 | 시계 오류 방어 |
| 토픽·페이로드 `deviceId` 불일치 | |

> **주의**: `messageId`는 측정값 하나마다 새 UUID여야 합니다. 센서 4종을 같은 messageId로
> 보내면 **1건만 저장되고 나머지가 조용히 버려집니다.**

### 장치 연결 상태

heartbeat를 받으면 `iot_device`를 ONLINE으로 갱신합니다. 스케줄러가 타임아웃(기본 90초)을
넘긴 장치를 OFFLINE으로 되돌립니다.

### 처리 스레드

`MqttPahoMessageDrivenChannelAdapter → DirectChannel → @ServiceActivator` 구조라
파싱·검증·저장·판정이 **하나의 MQTT 콜백 스레드에서 순차 실행**됩니다. 그래서

- 동시성 문제가 사실상 발생하지 않습니다(DB 제약은 안전망으로만 둡니다).
- 반대로 **이 경로에서 외부 API를 동기 호출하면 수집이 멈춥니다.** FCM 같은 것은
  반드시 트랜잭션 커밋 이후 비동기로 분리해야 합니다.

---

## 6. 센서 조회와 상태 판정

### 최신값 — `GET /plants/{plantId}/sensors/current`

센서 4종을 **항상 모두** 반환합니다(측정값이 없어도 항목 유지). 앱이 카드 배치를
고정할 수 있게 하기 위함입니다.

같은 응답을 장치도 받을 수 있습니다: `GET /api/v1/device/sensors/current`.
사용자 JWT 대신 `X-Device-Token`(사진 업로드와 같은 업로드 토큰)으로 인증하고,
plantId는 요청이 아니라 로봇의 활성 배정에서 서버가 정합니다. 젯슨의 대화 LLM이
실측 온습도로 답하기 위한 경로입니다(`docs/DEVICE-JETSON.md` 11절).

```
value < min          → LOW
min ≤ value ≤ max    → NORMAL
value > max          → HIGH

측정값 없음                     → NO_DATA
최신값이 신선도 창(기본 15분) 초과 → STALE
조도                            → NOT_APPLICABLE
```

`STALE`이 필요한 이유: 장치가 이틀 꺼져 있었는데 옛 값으로 "정상"이라고 말하면 안 됩니다.

### 조도를 순간값으로 판정하지 않는 이유

**밤에는 0 lux가 정상**입니다. 순간값으로 판정하면 매일 해 질 때 "조도 부족" 알림이 갑니다.
그리고 시드 데이터의 `illuminance_min/max_lux`는 25행 전부 NULL이며, 스키마 CHECK도
"둘 다 NULL이거나 둘 다 값이 있음"을 허용합니다 — 애초에 순간 판정을 전제하지 않은 설계입니다.

생육 기준은 대신 `daily_light_target_lux_hour`(하루 누적)와 `photoperiod_hours`(일조 시간)로
표현되어 있습니다. 그래서 조도는 [8절](#8-일일-광량과-일조-시간)에서 하루 단위로 판정합니다.

### 이력 — `GET /plants/{plantId}/sensors/history`

`interval=HOUR|DAY`로 시간 버킷 집계(평균·최소·최대·표본수)를 반환합니다.
페이지네이션이 아니라 집계인 이유: 5초 주기면 하루 34,560행/식물이라
원본을 페이지로 잘라 보내도 그래프를 만들 수 없습니다.

**타임존 안전한 버킷 계산**이 이 부분의 핵심입니다.

```sql
bucketIndex = FLOOR((TIMESTAMPDIFF(SECOND, '1970-01-01', measured_at) + :offsetSeconds)
                    / :bucketSeconds)
```
```java
bucketAt(UTC) = 1970-01-01 + bucketIndex × bucketSeconds − offsetSeconds
```

`CONVERT_TZ`를 쓰지 않습니다. 그 함수는 MySQL 타임존 테이블이 적재돼 있어야 하고
(공식 Docker 이미지에는 없습니다) 세션 타임존에도 의존합니다. 위 방식은 순수 산술이라
환경에 따라 결과가 달라지지 않습니다.

`offsetSeconds` 덕분에 **일 단위 버킷이 한국 시간 자정에 끊깁니다.** UTC 자정으로 끊으면
한국 시간 오전 9시에 날짜가 바뀌어 오전 데이터가 전날로 들어갑니다.

시각 규약: 저장과 응답은 UTC. `from`/`to`는 **오프셋 필수**이며 없으면 400입니다
(`LocalDateTime`으로 받으면 `+09:00`이 조용히 UTC로 오해되기 때문). 쿼리 스트링에서 `+`가
공백으로 해석되므로 `Z` 형식을 권장합니다.

---

## 7. 이상 알림

측정값이 저장된 직후(`SAVED`일 때만) 해당 식물·센서의 이상 상태를 다시 판정합니다.

### 두 종류의 잡음을 서로 다른 방법으로 막습니다

**① 순간 스파이크 → 중앙값**

```
대표값 = median(최근 N건)      N = potner.alert.sample-size (기본 3, 홀수 강제)
```

중앙값은 표본 하나가 튀어도 영향이 0입니다. `45 / 44 / 2`의 중앙값은 44라서 알림이 생기지 않습니다.

"최근 N건이 **전부** 이상"이라는 AND 조건은 쓰지 않았습니다. 노이즈가 큰 센서에서
**실제 이상을 놓치는** 실패 모드가 있기 때문입니다 — `35 / 41 / 34`는 평균 37로 명백히
부족한데 "전부 40 미만"은 만족하지 않습니다. 중앙값은 35로 정상 판정합니다.

**② 기준선 왕복 → Hysteresis (복귀 여유값)**

진입과 복귀 기준을 다르게 둡니다.

```
진입:  대표값 < min          또는  대표값 > max
복귀:  LOW  → 대표값 ≥ min + band
       HIGH → 대표값 ≤ max − band

band = (max − min) × potner.alert.hysteresis-ratio   (기본 0.1)
```

바질 토양수분 40~55라면 band는 1.5, 복귀 기준은 41.5입니다. 값이 39.6~41.2를 왕복해도
복귀 기준을 못 넘으므로 **알림이 1건으로 끝납니다.** 이게 없으면 15초마다
생성·해제가 반복되고 푸시도 그만큼 갑니다.

새벽 온도가 20.5~21.5를 왕복하는 경우도 마찬가지입니다(기준 21~29, band 0.8, 복귀 21.8).

### 상태 전이

| 최근 판정 | 활성 알림 | 동작 |
|---|---|---|
| 기준 밖 | 없음 | **생성** |
| 같은 방향 계속 | 있음 | 유지 (재발송 없음) |
| 반대 방향으로 전환 | 있음 | 기존 해제 + **신규 생성** |
| 복귀 기준 넘김 | 있음 | **해제** |
| 범위 안이지만 band 미달 | 있음 | 유지 ("회복 중") |
| 표본 부족 / 신선도 창 밖 | — | 아무것도 안 함 |

### 활성 알림은 식물·지표별 최대 1건

MySQL에는 부분 UNIQUE 인덱스가 없어서 **생성 컬럼**으로 표현했습니다.

```sql
active_key VARCHAR(80) GENERATED ALWAYS AS (
    IF(resolved_at IS NULL, CONCAT(plant_id, ':', metric_type), NULL)
) STORED,
UNIQUE (active_key)
```

해제되면 NULL이 되어 UNIQUE 대상에서 빠지므로 다음 알림을 만들 수 있고,
해제 이력도 그대로 남습니다.

> **MySQL 제약**: STORED 생성 컬럼의 기반 컬럼에는 `ON DELETE CASCADE`를 쓸 수 없습니다.
> 그래서 `fk_alert_plant`는 RESTRICT입니다. 식물 삭제는 소프트 삭제라 동작 차이는 없습니다.

### 지표 축

`metric_type`은 센서 종류와 1:1이 아닙니다.

```
TEMPERATURE / HUMIDITY / SOIL_MOISTURE   순간값 판정
DAILY_LIGHT / PHOTOPERIOD                하루 단위 판정
```

조회 API(`GET /alerts`)는 **문구를 담지 않습니다.** 서버가 한국어 문구를 만들면 앱의
표현 변경이 서버 배포에 묶입니다. 앱이 `plantName` + `metricType` + `deviation`으로 조립합니다.
(FCM 푸시 본문은 서버가 만들어야 하므로 그건 별개입니다.)

---

## 8. 일일 광량과 일조 시간

조도는 하루가 마감된 뒤 판정합니다. 서비스 타임존 기준 **새벽 2시에 전일분**을 집계합니다
(자정 직후로 하면 늦게 도착한 측정값을 놓칩니다).

### 적분

조도는 이산 표본이므로 계단 적분합니다.

```
구간초ᵢ = min(표본ᵢ와 표본ᵢ₊₁의 간격, max-gap-seconds)     기본 600초

누적 광량(lux·h) = Σ (측정값ᵢ × 구간초ᵢ) / 3600
일조 시간(h)     = Σ (측정값ᵢ ≥ light-on-threshold-lux ? 구간초ᵢ : 0) / 3600
커버리지(%)      = Σ 구간초ᵢ / 86400 × 100
```

`max-gap-seconds` 상한이 핵심입니다. 장치가 반나절 꺼져 있으면 마지막 측정값이
12시간 내내 유지된 것으로 계산되어 누적값이 크게 부풀려집니다. 상한으로 막고,
대신 커버리지가 작아지므로 데이터 부족이 드러납니다.

```
커버리지 < min-coverage-pct (기본 80) → INSUFFICIENT_DATA, 알림을 만들지도 해제하지도 않음
```

마지막 표본은 다음 표본이 없어 구간을 만들 수 없으므로 제외됩니다. 수집 주기가 짧으면
무시할 수 있는 크기입니다.

### 판정 두 축

```
누적 광량:  daily_light_min_lux_hour ≤ 누적 ≤ daily_light_max_lux_hour
일조 시간:  photoperiod_hours × (1 − r) ≤ 실측 ≤ photoperiod_hours × (1 + r)
            r = potner.daily-light.photoperiod-tolerance-ratio (기본 0.2)
```

**두 축이 독립적이라는 점이 중요합니다.** 총량이 목표와 정확히 같은데도 일조가 너무 길면
개화가 교란됩니다.

```
7,500 lux × 20시간 = 150,000 lux·h
  누적 광량 → NORMAL (목표 150,000)
  일조 시간 → HIGH   (목표 15h, 허용 12~18h, 실측 20h)
```

칼란디바처럼 **단일식물**은 꽃눈을 만들려면 긴 밤이 필요합니다. 시드 데이터를 보면
영양생장기 16시간 → 꽃눈형성기 **9.5시간**으로 목표 일조가 뚝 떨어집니다.
총량만 보면 절대 못 잡는 문제입니다.

### 허용 범위를 V7에서 채운 이유

시드는 목표값만 채워져 있고 허용 범위(`daily_light_min/max_lux_hour`)가 전부 NULL이라
판정이 불가능했습니다. V7이 목표값 대비 비율로 채웁니다.

```
min = target × 0.70
max = target × 1.30
```

코드 상수가 아니라 데이터로 넣은 이유는 종·단계별 조정 여지를 남기고,
`plant_growth_profile`이 이 값을 복사받아 사용자 맞춤값이 자동으로 따라오게 하기 위함입니다.

순간 조도(`illuminance_min/max_lux`)는 **의도적으로 비워 둡니다.**

### 참고 — 시드 데이터 검증

lux·h를 표준 DLI 단위로 환산해 값이 타당한지 확인할 수 있습니다.
일광 스펙트럼에서 1 µmol/m²/s ≈ 54 lux이므로:

```
DLI (mol/m²/일) ≈ 누적 lux·h / 15,000
```

| 종 · 단계 | 일조 | 목표 조도 | 누적 | ≈ DLI | 문헌 권장 |
|---|---|---|---|---|---|
| 방울토마토 유묘기 | 15h | 21,000 | 314,500 | 21.0 | 20~30 |
| 방울토마토 개화기 | 15h | 39,000 | 585,000 | 39.0 | 30+ |
| 바질 영양생장기 | 15h | 24,500 | 367,500 | 24.5 | 12~25 |
| 미니해바라기 개화기 | 13h | 40,500 | 526,500 | 35.1 | 고광량 작물 |

> lux는 사람 눈의 시감도 기준이라 광합성 유효 광량(PAR)과 정확히 비례하지 않습니다.
> 창가 자연광이면 위 계수가 적당하지만 **백색 LED 보광에서는 계수가 70~80으로 달라집니다.**
> 판정 결과가 체감과 다르다는 피드백이 나오면 이 지점을 의심하세요.

### 조회 — `GET /plants/{plantId}/daily-light?days=7`

- `today` — 진행 중인 오늘의 누적값과 진행률. **판정 결과를 담지 않습니다**(하루가 안 끝났으므로).
- `history` — 확정된 날들. 오늘은 포함하지 않습니다.

`plant_daily_light`는 판정 당시의 목표·허용 범위를 **스냅샷으로 저장**합니다.
생장 단계를 바꿔도 과거 기록의 판정이 소급 변경되지 않게 하기 위함입니다.

**알려진 한계**: 서버가 꺼져 있던 날은 건너뜁니다. 지난 날짜를 메우는 백필 작업은 없습니다.

---

## 9. 장치 상태 조회

`GET /plants/{plantId}/devices` — 담당 로봇과 하위 IoT 장치의 연결 상태.

로봇의 연결 상태는 **저장된 컬럼을 읽지 않고 계산합니다.**

```
robot.connectionStatus = 장치 중 하나라도 ONLINE ? ONLINE : OFFLINE
robot.lastSeenAt       = max(장치들의 last_seen_at)
```

heartbeat가 `iot_device`만 갱신하므로 `robot.connection_status` 컬럼은 생성 시 기본값
`'OFFLINE'`에 멈춰 있습니다. 그대로 응답하면 라즈베리가 잘 돌고 있어도 "로봇 오프라인"이
나갑니다. 그래서 `Robot` 엔티티는 그 두 컬럼을 **매핑하지 않습니다.**

로봇의 연결 상태는 독립된 사실이 아니라 하위 장치 상태의 함수입니다. 저장하면 같은 사실이
두 곳에 있게 되고 어긋날 수 있습니다.

반면 `battery_percent`와 `firmware_version`은 로봇 고유 데이터라 파생할 수 없습니다.
정당한 컬럼이며 **수집 연동이 없어 아직 항상 null**입니다.

로봇이 배정되지 않은 식물은 404가 아니라 `{ "robot": null, "devices": [] }`를 반환합니다.
식물은 실제로 존재하므로 404는 틀린 응답입니다.

---

## 10. 알림 설정

`GET`/`PATCH /users/me/notification-settings` — 토글 4종.

```
allEnabled       기본 true    마스터 스위치. 끄면 세부 설정과 무관하게 발송 안 함
pushEnabled      기본 true
plantCareEnabled 기본 true    센서 이상 및 케어 알림
marketingEnabled 기본 false   이벤트·공지. 회원가입의 선택 동의 항목
```

**행이 없으면 전부 기본값으로 취급합니다.** 덕분에 회원가입 시 행을 만들지 않아도 되고
기존 사용자 backfill 마이그레이션도 필요 없습니다. 조회는 행을 만들지 않고 첫 수정에서만
upsert합니다.

### 발송 대상 판정

발송 시 판정은 `UserNotificationSetting.allowsPush(NotificationCategory)`에 있습니다.

```
allEnabled && pushEnabled && 카테고리 토글
```

`PushTargetResolver`가 이 판정과 활성 토큰 조회를 함께 수행하고, 발송기는 그 결과만 받습니다.
설정 판정과 토큰 조회를 한곳에 묶은 이유는 **발송기가 설정을 읽는 것을 잊는 실패를
구조적으로 막기 위함**입니다. 판정에서 막히면 빈 목록이 나가므로 발송기는 아무것도 하지 않습니다.

---

## 10-1. FCM 기기 토큰

```
PUT    /api/v1/users/me/fcm-tokens/{installationId}   { token, platform }  → 204
DELETE /api/v1/users/me/fcm-tokens/{installationId}                        → 204
```

앱이 정의한 계약을 서버가 따릅니다. **식별자가 FCM 토큰이 아니라 Firebase 설치 ID(FID)입니다.**
토큰은 회전하지만 설치 ID는 앱 설치당 안정적이라, 토큰을 키로 두면 회전할 때마다 옛 행이
고아로 남습니다.

### 설치 ID가 PK인 이유

앱은 세션이 만료되어 강제 로그아웃될 때 **서버 등록을 지우지 않습니다.** 이미 죽은 Access
Token으로 DELETE를 부를 수 없으므로 클라이언트로서는 옳은 선택입니다. 그래서 사용자 A의 행이
남은 채 같은 기기에서 B가 로그인할 수 있습니다.

```
UNIQUE (user_id, installation_id)  → (A, FID)와 (B, FID)가 공존 → A의 알림이 B의 기기로 간다
PRIMARY KEY (installation_id)      → B의 등록이 A의 행을 대체한다
```

앱을 고치지 않고 **스키마가 이 상황을 막습니다.**

### 쓰기는 한 문장으로

`ON DUPLICATE KEY UPDATE`로 `user_id`까지 덮어씁니다. 조회 후 수정하면 `user_id`를 바꾸는
구간에 경쟁이 생깁니다.

> **주의**: `ON DUPLICATE KEY UPDATE`의 영향 행 수는 삽입 1, 갱신 2, 값이 그대로면 0입니다.
> `sensor_reading`의 중복 판정처럼 **영향 행 수로 분기하면 안 됩니다.**

### 해제는 소유자로 스코프

```sql
DELETE FROM fcm_token WHERE installation_id = :installationId AND user_id = :userId
```

`installation_id`만으로 지우면 남의 설치 ID를 알아낸 사람이 그 기기의 푸시를 끊을 수 있습니다.
지운 행이 0건이어도 **204**입니다 — 앱이 로그아웃 중 실패해 재시도할 수 있어야 하고,
존재 여부를 노출하지 않는 기존 방침과도 맞습니다.

경로 변수는 `@Valid`가 걸리지 않고 `ConstraintViolationException`이 전역 처리에 없어
`@Validated`를 쓰면 500이 됩니다. 그래서 설치 ID 길이는 **서비스에서 확인**합니다.

**아직 발송은 없습니다.** Firebase 연동과 실제 전송은 별도 작업입니다.

---

## 11. 계정 관리

| | |
|---|---|
| `PATCH /users/me` | 닉네임 변경 (앞뒤 공백 제거) |
| `PATCH /users/me/password` | 현재 비밀번호 확인 후 변경 → 204 |
| `DELETE /users/me` | 회원 탈퇴 → 204 |

### 비밀번호 변경 시 Refresh Token 전량 폐기

폐기하지 않으면 유출된 Refresh Token이 계속 유효해 변경의 목적이 반감됩니다.
변경한 기기도 재로그인이 필요하며, 앱은 204를 받으면 로그인 화면으로 보내면 됩니다.

같은 비밀번호로 바꾸려 하면 400(`PASSWORD_UNCHANGED`)입니다. 없으면 무의미한 동작으로
모든 기기가 로그아웃됩니다.

### 탈퇴는 소프트 처리

물리 삭제는 현재 스키마로 **불가능**합니다.

```
app_user 삭제  → plant.user_id, robot.user_id 가 ON DELETE RESTRICT 로 차단
plant 먼저 삭제 → alert.plant_id 가 RESTRICT 로 차단
robot 먼저 삭제 → sensor_reading.robot_id 가 RESTRICT 로 차단
```

외래키 4개를 바꿔야 하고 그 과정에서 연쇄 삭제 위험이 생깁니다.

소프트 탈퇴의 통상적 약점(남은 Access Token 유효 기간 동안 접근 가능)은 이 프로젝트에
없습니다. `JwtAuthenticationFilter`가 매 요청 계정 상태를 확인하므로 **탈퇴 직후부터
모든 요청이 403**이고 재로그인도 막힙니다.

**단, 데이터 행은 남습니다.** 앱 문구가 "영구 삭제"를 약속한다면 유예 기간 후 파기하는
별도 작업이 필요합니다.

---

## 12. API 목록

모든 응답 시각은 UTC입니다. `/api/v1/health`를 제외하면 전부 Bearer 인증이 필요합니다.

### 인증
```
POST   /api/v1/auth/signup
POST   /api/v1/auth/login
POST   /api/v1/auth/reissue
POST   /api/v1/auth/logout
```

### 사용자
```
GET    /api/v1/users/me
PATCH  /api/v1/users/me                            닉네임
PATCH  /api/v1/users/me/password
DELETE /api/v1/users/me                            탈퇴
GET    /api/v1/users/me/notification-settings
PATCH  /api/v1/users/me/notification-settings
```

### 기준정보
```
GET    /api/v1/plant-categories                    대분류·소분류(종) 계층
GET    /api/v1/plant-species                       활성 종 평면 목록
GET    /api/v1/plant-species/{id}/growth-stages    종별 지원 생장 단계
GET    /api/v1/plant-species/{id}/growth-stages/{lifeStageId}/requirement
```

### 내 식물
```
POST   /api/v1/plants
GET    /api/v1/plants
GET    /api/v1/plants/{plantId}
PATCH  /api/v1/plants/{plantId}                    이름 · 데려온 날짜
DELETE /api/v1/plants/{plantId}                    소프트 삭제
GET    /api/v1/plants/{plantId}/growth-profile
PATCH  /api/v1/plants/{plantId}/growth-profile     맞춤 기준
POST   /api/v1/plants/{plantId}/growth-profile/reset
PATCH  /api/v1/plants/{plantId}/growth-stage
```

### 센서 · 광량 · 장치
```
GET    /api/v1/plants/{plantId}/sensors/current
GET    /api/v1/plants/{plantId}/sensors/history?sensorType=&from=&to=&interval=
GET    /api/v1/plants/{plantId}/daily-light?days=
GET    /api/v1/plants/{plantId}/devices
```

### 로봇 대화
```
GET    /api/v1/plants/{plantId}/conversations/messages?beforeSeq=&size=
```

시간순(오래된 것부터)이고 스크롤백은 커서(`beforeSeq`) 방식입니다. `page`/`size` 가 아닌 이유는
대화가 뒤에 계속 붙기 때문입니다 — 스크롤하는 동안 새 발화가 들어오면 offset 이 밀려 같은 발화가
두 번 보이거나 빠집니다. 응답의 `nextBeforeSeq` 를 다음 요청의 `beforeSeq` 에 넣습니다.

날짜 구분선은 서버가 만들지 않습니다. `createdAt`(UTC)을 보고 앱이 묶습니다.

### 알림
```
GET    /api/v1/alerts?unreadOnly=&activeOnly=&page=&size=
PATCH  /api/v1/alerts/{alertId}/read               → 204
PATCH  /api/v1/alerts/{alertId}/dismiss            → 204  목록에서 치우기
DELETE /api/v1/alerts/{alertId}/dismiss            → 204  치우기 되돌리기
```

치우기는 **행을 지우지 않고** `alert.dismissed_at` 을 남깁니다. 목록 조회(`findOwned`)만 이 값을
보고, 행복도 점수·자동 급수·말리기·일기 생성은 치운 알림도 그대로 읽습니다.

지우면 안 되는 이유가 셋입니다.

1. **행복도 점수가 소급해서 바뀝니다.** `StatusReportService` 는 점수를 저장하지 않고 조회할 때마다
   그 날짜의 알림을 세어 100 점에서 깎습니다. 알림이 사라지면 지난 날의 점수가 올라갑니다.
2. **자동화가 다시 돕니다.** 자동 급수·말리기는 해소되지 않은 알림을 보고 트리거되고
   (`AutoDryingScheduler.dryOnce`), 열린 알림으로 중복 실행을 막습니다. 열린 알림을 지우면 서버는
   이상이 없었던 것으로 보고 같은 알림을 새로 만들며 체인을 다시 시작합니다.
3. **그날 일기의 근거가 달라집니다.** `PlantDaySummaryReader` 가 알림을 LLM 근거로 넘깁니다.

`resolved_at` 을 재활용하지 않는 이유도 2번입니다. 그쪽은 "이상이 해소됨" 이라 자동화가 읽는
값이고, `dismissed_at` 은 "사용자가 목록에서 치움" 입니다.

치우기는 읽음도 함께 남깁니다 — 목록에서 사라졌는데 안 읽음으로 남으면 홈 배지만 켜져 있고
사용자는 무엇이 남았는지 찾을 수 없습니다. 되돌리기는 읽음을 되돌리지 않습니다.

### 장치 (X-Device-Token 인증, 사용자 JWT 아님)
```
POST   /api/v1/device/photos                       라즈베리 사진 업로드
GET    /api/v1/device/sensors/current              젯슨 LLM 센서 최신값 조회
POST   /api/v1/device/conversations/turns          젯슨 LLM 대화 한 턴 저장
GET    /api/v1/device/conversations/messages       젯슨 LLM 대화 복원
```

### 기타
```
GET    /api/v1/health
```

**모든 식물 관련 조회는 로그인 사용자의 소유권을 검증**하며, 타인 식물은 404
(존재 여부를 노출하지 않기 위해 403이 아닙니다). 소프트 삭제된 식물도 404입니다.
예외는 위 장치 경로 둘뿐입니다 — 소유권 대신 장치 토큰을 검증하고, plantId를 받지
않고 로봇의 활성 배정에서 서버가 정하므로 자기 담당 식물만 접근됩니다.

OpenAPI 스펙을 파일로 뽑으려면:

```bash
SPRINGDOC_API_DOCS_ENABLED=true ./gradlew bootRun
```
```bash
curl -s http://localhost:8080/v3/api-docs > openapi.json
```

운영에서는 nginx가 8081을 인터넷에 공개하므로 Swagger를 켜지 않는 편이 안전합니다.

---

## 13. 설정 프로퍼티

`application.yml`의 `potner.*` 아래에 있습니다. 전부 환경변수로 덮어쓸 수 있습니다.

```yaml
potner:
  mqtt:
    enabled: false                      # 켜지 않으면 구독 자체가 뜨지 않음
    topic: potner/device/+/sensor/telemetry
    heartbeat:
      topic: potner/device/+/status/heartbeat
      offline-timeout-seconds: 90       # 넘으면 OFFLINE 전환
      offline-check-interval-seconds: 30
  daily-light:
    enabled: true
    cron: "0 0 2 * * *"                 # 서비스 타임존 기준
    zone: "Asia/Seoul"                  # @Scheduled 용 (아래 주의 참고)
    light-on-threshold-lux: 500         # 이 값 이상을 "빛 받는 중"으로 봄
    max-gap-seconds: 600                # 장치 침묵 구간 보정 상한
    min-coverage-pct: 80
    photoperiod-tolerance-ratio: 0.2
    max-history-days: 90
  alert:
    sample-size: 3                      # 중앙값 표본 수, 홀수여야 함
    hysteresis-ratio: 0.1               # 복귀 여유값 비율
    max-page-size: 100
  sensor:
    zone-offset: "+09:00"               # SQL 시각 연산용 (아래 주의 참고)
    freshness-threshold-minutes: 15     # 넘으면 STALE
    max-hour-interval-days: 14
    max-day-interval-days: 365
```

> **타임존 프로퍼티가 두 개인 이유**: `potner.sensor.zone-offset`은 SQL 산술에 쓰이므로
> 고정 오프셋(`+09:00`)이어야 하고, `potner.daily-light.zone`은 `@Scheduled`가
> `TimeZone.getTimeZone()`으로 해석하므로 IANA 지역 ID(`Asia/Seoul`)여야 합니다.
> **맨 오프셋을 넣으면 애플리케이션이 시작하지 않습니다.** 두 값은 같은 지역을 가리켜야 합니다.

---

## 14. 반복해서 부딪힌 제약과 결정

새로 기능을 붙일 때 같은 함정에 빠지지 않도록 정리했습니다.

### 타임존 — JVM이 UTC라는 전제

앱은 `hibernate.jdbc.time_zone: UTC`와 `spring.jackson.time-zone: UTC`로
**JVM 기본 타임존이 UTC라는 전제**로 동작합니다.

JVM이 UTC가 아니면 Hibernate 경로와 JdbcTemplate 경로가 어긋납니다(한국이면 9시간).
그래서 `build.gradle`이 테스트 JVM을 UTC로 고정합니다.

```groovy
tasks.withType(Test).configureEach {
    systemProperty 'user.timezone', 'UTC'
}
```

**운영 컨테이너가 UTC인 것은 Docker 기본값 덕분이며 아무 곳에도 명시돼 있지 않습니다.**
누가 로그를 한국 시간으로 보려고 `TZ: Asia/Seoul`을 추가하면 그 순간부터 저장되는
센서 시각이 밀립니다. 로그 표시만 바꾸려면 타임존이 아니라 logback 패턴에
`%d{yyyy-MM-dd HH:mm:ss.SSS, Asia/Seoul}`처럼 표시 타임존을 지정하세요.

### MySQL 제약 세 가지

1. **부분 UNIQUE 인덱스가 없습니다.** "활성 행만 유일"은 생성 컬럼 + UNIQUE로 우회합니다.
2. **STORED 생성 컬럼의 기반 컬럼에는 CASCADE 참조 동작을 쓸 수 없습니다.**
3. `CONVERT_TZ`는 타임존 테이블 적재가 필요하고 세션 타임존에 의존합니다. 쓰지 않습니다.

### `DATETIME(0)`은 소수 초를 반올림합니다

버리지 않고 올립니다. 현재 시각을 그대로 저장하면 조회 상한(`< now`)을 넘겨
방금 넣은 행이 조회에서 빠질 수 있습니다. 테스트에서 특히 조심하세요.

### 스케줄러를 테스트에서 끄면 검증도 사라집니다

`@ConditionalOnProperty`로 스케줄러 빈을 만들지 않으면 `@Scheduled`의 cron·zone 검증도
실행되지 않아 잘못된 값이 배포까지 갑니다(실제로 한 번 발생했습니다).
**끄지 말고 사실상 실행되지 않는 스케줄로 두세요.**

```yaml
# application-test.yml
potner.daily-light:
  enabled: true
  cron: "0 0 3 1 1 *"   # 매년 1월 1일 03시 = 테스트 중 거의 안 걸림
```

### 배포 디렉토리는 파이프라인이 덮어씁니다

EC2의 배포 폴더 내용은 매 배포마다 GitLab 최신 내용으로 교체됩니다.
**서버에서 직접 수정하면 다음 배포에 사라집니다.** Firebase 자격증명 같은 비밀값은
Jenkins Credential로 주입해 sync 경로 **밖**에 두세요.

---

## 15. 마이그레이션 이력

| | 내용 |
|---|---|
| V1 | `app_user`, `refresh_token` |
| V2 | 기준정보 테이블 4개 + 시드(종 6, 단계 9, 기준 25행) |
| V3 | `plant`, `plant_growth_profile` |
| V3_5 | `robot`, `plant_device_assignment`, `sensor_reading` |
| V4 | `sensor_reading` 중복 방지 제약 조정 |
| V5 | `iot_device` 분리, 측정값 1건 = 메시지 1건으로 변경 |
| V6 | `alert` (+ 활성 유일성 생성 컬럼) |
| V7 | `plant_daily_light` + 광량 허용 범위 채우기 |
| V8 | `user_notification_setting` |
| V9 | `plant.adopted_date`, `plant_life_stage.description` |
| V10 | `plant_device_assignment` 재배정 허용 (활성 유일성 생성 컬럼, FK RESTRICT 전환) |
| V11 | `plant_photo`, `robot.upload_token_hash` |
| V12 | `fcm_token` (설치 ID가 PK) |

---

## 16. 아직 없는 것

### 🔴 장치가 MQTT를 발행하지 않습니다

서버는 MQTT 구독이 완성돼 있지만, `Raspberry-master`의 수집기는 **MQTT를 쓰지 않습니다.**
`POST /api/v1/sensors/soil`로 HTTP POST를 하는데 그 엔드포인트는 서버에 없고,
대상 주소도 `http://127.0.0.1:8080`(파이 자신)이며 인증 헤더도 없습니다.
실패를 로그만 남기고 루프를 계속하는 구조라 조용히 계속 실패합니다.

**즉 `sensor_reading`이 비어 있을 가능성이 높고, 조회·판정 기능은 읽을 데이터가 없는
상태입니다.** 라즈베리에 MQTT 발행을 붙이는 것이 우선입니다(서버 수정 0).
서버에 HTTP 수집 엔드포인트를 만들면 Mosquitto·ACL·구독·heartbeat 구현이 무용지물이 됩니다.

### 없는 기능

| | 비고 |
|---|---|
| **FCM 발송** | 토큰 등록과 발송 대상 판정은 완료. Firebase 연동과 실제 전송이 남음. MQTT 수신 스레드에서 동기 호출하면 센서 수집이 멈추므로 커밋 이후 비동기로 분리해야 함 |
| 장치 등록·페어링 API | 테이블은 있으나 등록 수단이 없어 수동 INSERT 필요 |
| 로봇 배터리 수집 | 컬럼은 있음. MQTT 계약 확정 필요 |
| MQTT 제어(급수·팬·LED) | publish 자체가 없음. ACL에 `command/#`만 예약 |
| 사진 업로드 | `plant.profile_image_url` 컬럼은 있으나 코드에서 미사용. 저장소 미도입 |
| 성장기록 (일기·포토·개화·성장비교) | 테이블 없음 |
| 건강 점수 | 산출 규칙이 정의된 적 없음 |
| 급수 이력 | 테이블 없음 |
| 약관 동의 기록 | 저장할 곳 없음 |
| 소셜 로그인 | `password_hash` nullable로 준비만 됨 |
| `app_user.name` (이름 ≠ 닉네임) | 디자인 내부 불일치로 보류 |

### 정리하면 좋은 것

- `robot.connection_status` / `robot.last_seen_at` — 매핑하지 않는 죽은 컬럼
- `plant_device_assignment`의 `UNIQUE(plant_id)` / `UNIQUE(robot_id)` — 컬럼 전체 UNIQUE라
  `unassigned_at`이 있어도 배정 이력을 쌓을 수 없습니다. 조회 코드는 이력을 전제합니다.
  이력을 유지하려면 생성 컬럼으로 "활성 배정만 유일"을 표현해야 합니다.
- `compose.yml`에 타임존 명시 (14절 참고)
- Jenkinsfile 성공 메시지 — develop에서도 "운영 배포 성공"으로 찍힘

---

## 17. 테스트 전략

| | |
|---|---|
| 단위 | 판정 로직, 서비스 분기, 소유권 검증. Mockito, Docker 불필요 |
| 통합 | `PotnerApplicationTests` 한 클래스. MySQL Testcontainers, `@Tag("integration")` |

SQL이 관여하는 것은 **반드시 통합 테스트로** 검증합니다. 시간 버킷 집계, 조도 적분,
생성 컬럼 UNIQUE, 마이그레이션 결과는 단위 테스트로 잡히지 않습니다.

통합 테스트가 검증하는 대표 시나리오:

- 일 단위 버킷이 한국 시간 자정에 끊기는지
- 7,500 lux × 20시간이 누적은 NORMAL, 일조는 HIGH로 갈리는지
- 알림 생성 → 중복 없음 → hysteresis 유지 → 해제 → 재생성
- 스파이크 1건과 조도가 알림을 만들지 않는지
- heartbeat 하나로 로봇 상태가 ONLINE으로 파생되는지 (컬럼은 여전히 OFFLINE)
- 탈퇴 후 Access Token 재사용·재발급·재로그인이 모두 막히는지

CI는 clean 상태에서 `test`와 `integrationTest`를 모두 실행합니다.
`Server-master`에 머지될 때만 배포가 돕니다.
