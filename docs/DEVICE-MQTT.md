# Potner 장치 연동 가이드

라즈베리파이·젯슨을 서버에 붙이려는 사람과, 앱에 기기 연결 화면을 만드는 사람을 위한 문서입니다.
**무엇을 어떻게 보내야 하고, 안 될 때 어디를 보는지**를 다룹니다.

그렇게 설계한 이유는 `docs/BACKEND.md` 5절(센서 수집)과 7절(이상 알림)에 있습니다. 두 문서가
어긋나면 코드가 기준입니다.

기준 시점: 2026년 7월 28일.

---

## 1. 연결 구조

```
app_user
 └─ robot  (device_uid, name, upload_token_hash)          "본체" 1대
     ├─ iot_device  device_uid=raspberry-01  RASPBERRY_PI
     └─ iot_device  device_uid=jetson-01     JETSON_ORIN

     robot ──── plant_device_assignment ──── plant
                (활성 상태에서 1:1)
```

배정은 **로봇 단위**입니다. 센서값이 들어오면 서버가
`iot_device.device_uid → robot → 활성 배정 → plant` 순으로 거슬러 올라가 어느 식물의 값인지
정합니다. 그래서 파이와 젯슨을 같은 로봇에 달면 둘 다 같은 식물로 귀속됩니다.

활성 배정은 식물당 하나, 로봇당 하나입니다. 재배정은 되고 이력은 남습니다
(`V10__allow_plant_device_reassignment.sql`).

---

## 2. 기기 등록 — 서버에 "이 장치는 내 것"이라고 알리는 단계

**등록이 없으면 장치가 아무리 정확히 보내도 전부 버려집니다.** 실제로 등록 전에 도착한 메시지가
`IoT device was not found`로 버려진 사례가 있었습니다.

등록은 기기당 **한 번**이고 DB에 남습니다. 앱을 껐다 켜도, 재로그인해도, 서버를 재배포해도
유지됩니다. 로그인할 때마다 하는 FCM 토큰 등록과는 성격이 다릅니다.

| 순서 | 엔드포인트 | 본문 |
|---|---|---|
| 1 | `POST /api/v1/plants` | `speciesId`, `lifeStageId`, `name` |
| 2 | `POST /api/v1/robots` | `deviceUid`, `name` → `robotId`, `uploadToken` 반환 |
| 3 | `POST /api/v1/robots/{robotId}/devices` | `deviceUid`, `deviceType` (기기마다 1회) |
| 4 | `POST /api/v1/plants/{plantId}/assignment` | `robotId` |

`deviceType`은 `RASPBERRY_PI` 또는 `JETSON_ORIN`입니다.

`deviceUid` 형식은 `[A-Za-z0-9][A-Za-z0-9_-]*`, 최대 100자입니다. UUID일 필요가 없고, 사용자가
손으로 입력하는 화면을 만들 거라면 `raspberry-01` 같은 짧은 슬러그가 낫습니다.

### 실패 응답

| 상황 | 코드 | HTTP |
|---|---|---|
| 이미 등록된 식별자 | `DEVICE_UID_ALREADY_REGISTERED` | 409 |
| 이미 배정된 로봇 | `ROBOT_ALREADY_ASSIGNED` | 409 |
| 이미 배정된 식물 | `PLANT_ALREADY_ASSIGNED` | 409 |

`device_uid`는 **전역 UNIQUE**입니다. 한 번 등록된 문자열은 다른 사용자가 쓸 수 없습니다.

### 등록이 됐는지 확인하는 방법

`GET /api/v1/plants/{plantId}/devices`가 장치별 `connectionStatus`를 돌려줍니다.
등록 직후에는 `OFFLINE`이고, 장치가 하트비트를 보내기 시작하면 `ONLINE`으로 바뀝니다.

앱에서 사용자가 식별자를 직접 입력하는 화면을 만든다면 **이 폴링을 반드시 넣으세요.**
30~60초 안에 판정되므로 오타를 사용자가 그 자리에서 알 수 있습니다. 이 확인 단계가 없으면
잘못 입력해도 아무 일도 일어나지 않고, 원인을 찾을 방법이 없습니다.

---

## 3. MQTT 접속

| 항목 | 값 |
|---|---|
| 브로커 | `i15e104.p.ssafy.io` |
| 포트 | **1884** (도커 네트워크 내부는 1883) |
| 계정 | 장치별 mosquitto 계정 (`raspberry-01`, `jetson-01`) |
| 비밀번호 | `infra/mosquitto/config/password.txt`. **git에 없고 저장소에 적지 않습니다** |
| QoS | 1 권장 |
| TLS | 없음 (평문) |

**client ID를 서버와 겹치게 쓰면 안 됩니다.** 서버가 `potner-backend-prod`를 쓰는데 같은 값으로
접속하면 브로커가 기존 세션을 끊어 서로를 계속 밀어냅니다. `<device_uid>-collector` 처럼
장치별로 유일한 값을 쓰세요. CI에서 실제로 이 사고가 있었습니다.

계정별 권한은 `infra/mosquitto/config/acl`에 있습니다. 지금은 `raspberry-01`과 `jetson-01`이
하드코딩되어 있고, 새 계정을 추가하려면 git 커밋과 배포가 필요합니다(9절 참고).

---

## 4. 토픽과 `device_uid` 일치 규칙

```
potner/device/<device_uid>/sensor/telemetry     센서 측정값
potner/device/<device_uid>/status/heartbeat     생존 신호
```

**같은 문자열이 네 곳에서 일치해야 합니다.**

| # | 위치 | 대조 방식 |
|---|---|---|
| 1 | 토픽 세그먼트 | 정규식 `^potner/device/([^/]{1,100})/sensor/telemetry$` |
| 2 | 페이로드 `deviceId` | 1번과 Java `String.equals` — **대소문자 구분** |
| 3 | `iot_device.device_uid` | MySQL 조회. 콜레이션이 `ai_ci` 라 **대소문자 무시** |
| 4 | mosquitto 계정명 | 브로커 ACL. 대소문자 구분 |

2번과 3번의 대조 방식이 달라서 이런 비대칭이 생깁니다.

| 토픽 | 페이로드 | DB | 결과 |
|---|---|---|---|
| `raspberry-01` | `raspberry-01` | `raspberry-01` | 정상 |
| `raspberry-01` | `Raspberry-01` | `raspberry-01` | **2번에서 버려짐** |

장치 코드에서 **상수 하나를 토픽 조립과 페이로드에 함께 쓰세요.** 문자열을 두 군데에 따로
적으면 이 함정에 걸립니다.

---

## 5. 센서 페이로드

```json
{
  "messageId": "3f2b1c8a-5d4e-4f6a-9b0c-1d2e3f4a5b6c",
  "deviceId": "raspberry-01",
  "sensorType": "SOIL_MOISTURE",
  "value": 32.5,
  "unit": "PERCENT",
  "measuredAt": "2026-07-28T04:15:00Z"
}
```

**측정값 하나당 메시지 하나입니다.** 온습도·조도까지 보내려면 메시지 4건이고, 각각 `messageId`가
달라야 합니다. 같은 ID로 묶어 보내면 1건만 저장되고 나머지는 조용히 사라집니다.

| `sensorType` | `unit` | 허용 범위 |
|---|---|---|
| `TEMPERATURE` | `CELSIUS` | -40 ~ 85 |
| `HUMIDITY` | `PERCENT` | 0 ~ 100 |
| `SOIL_MOISTURE` | `PERCENT` | 0 ~ 100 |
| `ILLUMINANCE` | `LUX` | 0 이상 |

두 값 모두 서버 enum과 **대소문자까지 정확히** 같아야 합니다. `"soil_moisture"` 처럼 보내면
JSON 파싱 단계에서 통째로 버려집니다.

`measuredAt`은 오프셋을 포함한 ISO-8601입니다. `Z`도 `+09:00`도 됩니다. **현재 시각보다 10분
이상 미래면 버려지므로 장치의 NTP 동기화가 필요합니다.**

---

## 6. 하트비트 페이로드

```json
{
  "messageId": "9c8b7a6d-...",
  "deviceId": "raspberry-01",
  "sentAt": "2026-07-28T04:15:00Z"
}
```

30초 주기를 권장합니다. 90초간 없으면 서버가 `OFFLINE`으로 표시합니다.

**하트비트를 보내지 않으면 앱의 기기 연결 화면이 영원히 `OFFLINE`입니다.** 센서값만 보내도
데이터는 쌓이지만 사용자에게는 "연결 안 됨"으로 보입니다.

---

## 7. 안 될 때 — 로그 문구로 원인 찾기

MQTT는 응답이 없는 단방향이라 장치는 실패를 알 수 없습니다. **서버 로그가 유일한 진단 수단입니다.**

```bash
docker logs --since 5m potner-infra-test-backend-1 2>&1 | grep -iE "sensor reading|heartbeat|alert|push"
```

검사는 순서대로 이루어지고 단계마다 다른 문구를 남깁니다.

| 순서 | 검사 | 실패 시 로그 | 흔한 원인 |
|---|---|---|---|
| 1 | 토픽 정규식 | `Invalid MQTT sensor telemetry topic` | 토픽 오타, 접미사 |
| 2 | JSON 파싱 · enum 값 | `Invalid MQTT sensor telemetry JSON` | enum 대소문자, 깨진 JSON |
| 3 | 필수값 | `Invalid MQTT sensor telemetry values: fields=...` | 누락 필드 (이름이 찍힙니다) |
| 4 | 타입↔단위↔범위 | `Invalid MQTT sensor telemetry type/unit/value` | 5절 표 불일치 |
| 5 | 미래 시각 | `Invalid MQTT sensor telemetry measuredAt` | 장치 시계 틀어짐 |
| 6 | 토픽 ↔ 페이로드 `deviceId` | `MQTT sensor telemetry deviceId mismatch` | 4절 비대칭 |
| 7 | 저장 | `IoT device was not found` | **2절 등록 안 됨** |
| 7 | 저장 | `robot was not found` | 데이터 정합성 문제 |
| 7 | 저장 | `active plant assignment was not found` | 배정(4단계) 안 함 |
| 7 | 저장 | (아무것도 안 나옴) | **`messageId` 재사용** — 아래 참고 |

성공하면 `MQTT sensor reading saved: messageId=..., deviceId=..., sensorType=...` 가 찍힙니다.

1~6번은 장치 쪽 문제, 7번은 대체로 서버 등록 문제입니다.

### `messageId` 재사용은 로그에 안 보입니다

중복 판정만 `log.debug`이고 나머지는 전부 `warn`·`info`입니다. 기본 로그 레벨이 INFO라
**중복으로 버려진 메시지는 아무 흔적도 남기지 않습니다.** 증상은 `saved` 줄이 보내는 건수보다
적다는 것뿐입니다.

센서 4종을 같은 `messageId`로 보내면 1건만 저장되고 3건이 사라지는데, 로그만 보면 원인을 알 수
없습니다. 보낸 건수와 `saved` 건수를 세어 비교하세요.

```bash
docker logs --since 5m potner-infra-test-backend-1 2>&1 | grep -c "sensor reading saved"
```

---

## 8. 알림이 뜨는 조건

측정값이 저장됐다고 알림이 생기는 것은 아닙니다. 다음을 **모두** 만족해야 합니다.

| # | 조건 | 설정 |
|---|---|---|
| 1 | 같은 식물·센서로 3건 이상 | `potner.alert.sample-size` |
| 2 | 3건이 최근 15분 안 | `potner.sensor.freshness-threshold-minutes` |
| 3 | 3건의 **중앙값**이 기준 밖 | 순간 스파이크 방어 |
| 4 | 해당 지표에 열린 알림이 없음 | 활성 알림은 지표별 1건 |

3번 때문에 **3건 중 2건 이상**이 기준 밖이어야 합니다. 45/44/2 처럼 한 건만 튀면 중앙값이 44라
알림이 생기지 않습니다.

**수집 주기가 5분보다 짧아야 합니다.** 주기가 길면 3건을 모으는 사이 첫 건이 신선도 창 밖으로
밀려나 조건이 영원히 차지 않습니다.

### 복귀와 재알림

4번 때문에 한 번 뜬 알림은 정상 범위로 **복귀해야** 다시 뜹니다. 복귀 기준은 진입 기준보다
여유가 있습니다.

```
band = (max − min) × 0.1          potner.alert.hysteresis-ratio
복귀  값 ≥ min + band  (LOW 였던 경우)
      값 ≤ max − band  (HIGH 였던 경우)
```

기준선 근처에서 값이 오갈 때 생성·해제가 반복되는 것을 막기 위함입니다.

지표는 서로 독립입니다. 토양수분 알림이 열려 있어도 온도 알림은 따로 뜹니다.

### 조도는 순간값으로 알림이 나지 않습니다

`ILLUMINANCE`는 저장은 되지만 즉시 판정 대상이 아닙니다. **밤에는 0 lux가 정상**이라 순간
판정을 하면 매일 해 질 때 "조도 부족" 알림이 갑니다. 시드 데이터의 `illuminance_min/max_lux`가
전부 NULL인 것도 같은 이유입니다.

대신 새벽 2시 배치가 하루 누적 광량(`DAILY_LIGHT`)과 일조 시간(`PHOTOPERIOD`)으로 전일분을
판정합니다. 즉석 시연에는 쓸 수 없습니다.

---

## 9. 알려진 공백

지금 구조로 시연은 되지만 제품 흐름으로는 빈 곳이 있습니다. 모르고 순서를 잘못 잡으면 헛일이
되므로 적어 둡니다.

**브로커 계정을 발급할 방법이 없습니다.** ACL에 계정이 하드코딩되어 git에 커밋돼 있고
`password.txt`는 수동 관리입니다. 새 기기를 붙이려면 커밋과 배포가 필요합니다. 앱에 기기 연결
화면을 만들어도 이것이 해결되지 않으면 사용자가 자기 기기를 연결할 수 없습니다.
**앱 화면보다 이쪽이 선결입니다.**

**장치가 필요한 자격증명이 두 개인데 전달 경로가 없습니다.** 센서용 mosquitto 계정과 사진
업로드용 `uploadToken`입니다. 후자는 로봇 등록 응답으로 나오지만 그걸 장치에 넣는 것은 현재
사람이 손으로 합니다.

**사용자가 `device_uid`를 알아낼 방법이 정해지지 않았습니다.** 기기 라벨 QR, 본체 인쇄,
BLE 페어링 중 하나여야 합니다. 블루투스는 서버 작업 범위가 아닙니다.

**삭제 API가 없습니다.** `DELETE`는 배정 해제 하나뿐입니다. 게다가 `sensor_reading`이
`robot_id`와 `source_device_id`를 RESTRICT로 참조하므로, **한 번이라도 측정값을 보낸 로봇은
그냥 지워지지 않습니다.** 생성 컬럼 때문에 CASCADE로 바꿀 수도 없습니다(V10 주석 참고).
제대로 만들려면 "측정값을 어떻게 할 것인가"를 먼저 정해야 하는 설계 결정입니다.

**앱에 기기 등록 화면이 없습니다.** 서버 API는 완비되어 있고 앱이 호출하지 않습니다.

---

## 10. 시연 준비

### 등록 초기화

리허설을 반복하거나 기기 연결 장면을 라이브로 보여주려면 기존 등록을 지워야 합니다. 삭제 API가
없으므로 SQL로 합니다. 계정과 식물은 남기고 기기 등록만 지웁니다.

RESTRICT 때문에 **순서가 중요합니다.** 안쪽부터 지워야 합니다.

```sql
SET @plant := '<plantId>';
SET @robot := '<robotId>';

START TRANSACTION;
DELETE FROM alert                    WHERE plant_id = @plant;
DELETE FROM sensor_reading           WHERE robot_id = @robot;
DELETE FROM plant_device_assignment  WHERE robot_id = @robot;
DELETE FROM iot_device               WHERE robot_id = @robot;
DELETE FROM robot                    WHERE robot_id = @robot;
COMMIT;
```

`alert`를 함께 지우는 것은 8절 4번 때문입니다. 활성 알림이 남아 있으면 첫 푸시가 가지 않습니다.

### 장치 없이 알림 만들기

하드웨어 없이 알림 경로를 시연하거나 검증할 때 씁니다.

```bash
read -sp 'device pw: ' PW; echo

fire() {   # fire <sensorType> <unit> <value>
  for i in 1 2 3; do
    docker exec potner-infra-test-mosquitto-1 mosquitto_pub -h 127.0.0.1 -p 1883 \
      -u raspberry-01 -P "$PW" \
      -t 'potner/device/raspberry-01/sensor/telemetry' \
      -m "{\"messageId\":\"$(cat /proc/sys/kernel/random/uuid)\",\"deviceId\":\"raspberry-01\",\"sensorType\":\"$1\",\"value\":$3,\"unit\":\"$2\",\"measuredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
    sleep 1
  done
  docker logs --since 1m potner-infra-test-backend-1 2>&1 | grep -iE "result=|Alert push handled" | tail -3
}
```

같은 지표를 LOW → HIGH로 번갈아 쏘면 `result=SWITCHED`가 나면서 기존 알림을 닫고 새 알림을
열어 푸시가 또 갑니다. 8절의 복귀 조건을 맞출 필요 없이 반복 시연이 됩니다.

### 앱 쪽 조건

**debug 빌드여야 합니다.** nginx가 TLS 없이 평문 HTTP만 서빙하는데, `usesCleartextTraffic`이
debug 소스셋에만 있어서 release 빌드는 서버에 닿지 못합니다.

```bash
flutter run --dart-define=API_BASE_URL=http://i15e104.p.ssafy.io/api/v1
```

알림이 뜨는 순간 **앱은 백그라운드에 있어야 합니다.** 포그라운드면 시스템 알림 대신 앱이 직접
처리해서 화면에 안 보일 수 있습니다.

---

## 11. 라즈베리파이 참고 구현

`Raspberry-master`의 `sensor-collector`는 현재 존재하지 않는 `POST /api/v1/sensors/soil`로
HTTP POST를 하고 있습니다. 대상도 `http://127.0.0.1:8080`(파이 자신)이고 인증도 없습니다.
아래 형태로 바꿔야 합니다. `requirements.txt`에 `paho-mqtt>=2.0` 추가가 필요합니다.

```python
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

import paho.mqtt.client as mqtt

# 서버 enum 과 정확히 일치해야 한다. 대소문자도 구분한다.
SENSOR_UNITS = {
    "TEMPERATURE": "CELSIUS",     # -40 ~ 85
    "HUMIDITY": "PERCENT",        # 0 ~ 100
    "SOIL_MOISTURE": "PERCENT",   # 0 ~ 100
    "ILLUMINANCE": "LUX",         # 0 이상
}


@dataclass(frozen=True)
class PublishResult:
    ok: bool
    message_id: Optional[str]
    message: str


class MqttTelemetryPublisher:
    """측정값을 MQTT 로 서버에 보낸다. 측정값 하나당 메시지 하나."""

    def __init__(
        self,
        host: str,
        port: int = 1884,
        *,
        username: str,
        password: str,
        device_uid: str = "raspberry-01",
        qos: int = 1,
    ) -> None:
        self.host, self.port = host, port
        self.username, self.password = username, password
        self.device_uid = device_uid
        self.qos = qos
        # 토픽 세그먼트와 페이로드 deviceId 는 서버가 문자열 비교로 대조한다.
        # 대소문자까지 같아야 하므로 반드시 같은 변수에서 만든다.
        self.topic = f"potner/device/{device_uid}/sensor/telemetry"
        self._client: Optional[mqtt.Client] = None

    def connect(self) -> None:
        # client_id 는 브로커에서 유일해야 한다. 서버가 potner-backend-prod 를 쓰므로
        # 겹치면 브로커가 서로를 계속 끊어낸다.
        client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"{self.device_uid}-collector",
        )
        client.username_pw_set(self.username, self.password)
        client.connect(self.host, self.port, keepalive=60)
        client.loop_start()
        self._client = client

    def publish(self, sensor_type: str, value: float) -> PublishResult:
        if self._client is None:
            return PublishResult(False, None, "not connected")

        unit = SENSOR_UNITS.get(sensor_type)
        if unit is None:
            return PublishResult(False, None, f"unknown sensorType: {sensor_type}")

        payload = {
            # 측정값마다 새로 만든다. 재사용하면 서버가 중복으로 보고 버린다.
            "messageId": str(uuid.uuid4()),
            "deviceId": self.device_uid,
            "sensorType": sensor_type,
            "value": round(float(value), 2),
            "unit": unit,
            # 서버 시각보다 10분 이상 미래면 버려진다. NTP 동기화가 필요하다.
            "measuredAt": datetime.now(timezone.utc)
                .isoformat(timespec="seconds")
                .replace("+00:00", "Z"),
        }

        info = self._client.publish(self.topic, json.dumps(payload), qos=self.qos)
        info.wait_for_publish(timeout=5)
        return PublishResult(
            info.rc == mqtt.MQTT_ERR_SUCCESS, payload["messageId"], "published"
        )

    def close(self) -> None:
        if self._client is not None:
            self._client.loop_stop()
            self._client.disconnect()
```

호출부입니다. 비밀번호는 환경변수로 받고 코드나 git에 넣지 않습니다.

```python
pub = MqttTelemetryPublisher(
    host="i15e104.p.ssafy.io",
    username="raspberry-01",
    password=os.environ["MQTT_PASSWORD"],
)
pub.connect()

pub.publish("SOIL_MOISTURE", reading["soil_moisture_pct"])
pub.publish("TEMPERATURE",   reading["temperature_c"])
pub.publish("HUMIDITY",      reading["humidity_pct"])
pub.publish("ILLUMINANCE",   reading["lux"])
```

하트비트는 같은 클라이언트로 `potner/device/<uid>/status/heartbeat`에 6절 페이로드를 30초마다
발행하면 됩니다.
