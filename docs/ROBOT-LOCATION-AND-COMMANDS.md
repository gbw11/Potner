# 로봇 좌표와 이동 명령

로봇이 어디로 가는지를 서버가 어떻게 정하고, 그 좌표를 어떤 형태로 장치에 넘기는지 적는다.
좌표를 다루는 코드를 고칠 때 여기부터 읽으면 된다.

관련 문서: MQTT 토픽·페이로드 계약 전반은 [DEVICE-MQTT.md](DEVICE-MQTT.md).

---

## 1. 좌표의 출처는 서버 한 곳뿐이다

`robot_location` 테이블이 좌표의 유일한 출처다. 로봇 파라미터(`station_poses` 같은 것)에
좌표를 두면 출처가 둘이 되어, 앱에서 자리를 옮겨도 로봇이 예전 좌표로 간다. 그래서 서버는
이동 명령에 **목적지 이름과 좌표를 함께 실어 보낸다.**

이름은 로봇 로그와 상태 보고에서 사람이 읽을 값이고, 로봇이 실제로 쓰는 것은 좌표다.

```
robot_location (서버)  ──명령에 좌표 동봉──▶  로봇은 받은 좌표로 이동
```

---

## 2. 위치 종류는 넷이다

`RobotLocationType` ([RobotLocationType.java](../backend/src/main/java/com/potner/location/domain/RobotLocationType.java))

| 종류 | 쓰임 | 스테이션 코드 |
|---|---|---|
| `WATER_STATION` | 급수 스테이션. 물 부족 보고가 여기로 귀속된다 | **필수** |
| `HOME` | 대기 장소. 임무가 없을 때 돌아와 있는 자리 | 없음 |
| `SUNLIGHT` | 햇빛 자리. 누적 광량이 모자라면 여기로 보낸다 | 없음 |
| `GREETING` | 마중 지점. 사용자가 귀가하면 맞으러 나간다 | 없음 |

넷 중 물리 장치는 `WATER_STATION` 뿐이라 그것만 코드를 갖는다. 나머지는 SLAM 지도 위의
좌표일 뿐이다. 이 구분이 무너지면 앱 등록 화면이 그늘 자리에도 코드를 요구하게 된다.

DB 제약 `ck_robot_location_station_code` 가 이 규칙을 강제한다.

---

## 3. 좌표를 넣는 API

`/api/v1/robots/{robotId}/locations` ([RobotLocationController.java](../backend/src/main/java/com/potner/location/presentation/RobotLocationController.java))

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| `POST` | `/locations` | 위치 종류를 등록한다. 좌표 없이 자리만 먼저 만든다 |
| `GET` | `/locations` | 등록된 위치와 좌표를 모두 조회한다 |
| `PUT` | `/locations/{type}/pose` | 그 자리의 좌표를 넣거나 고친다 |

등록과 좌표 입력이 나뉘어 있다. 사용자가 앱에서 자리를 먼저 만들고, 로봇을 그 자리로
데려간 뒤 "여기로 지정" 을 눌러 좌표를 채우는 흐름이기 때문이다.

### 좌표 형식

`UpdateLocationPoseRequest`

```json
{ "x": 1.250, "y": -0.480, "yaw": 1.5708 }
```

- `x`, `y` — `map` 프레임 기준 미터. 범위 ±99999.999
- `yaw` — 라디안. 범위 **-3.1416 ~ 3.1416** (±π)

세 값 모두 필수다. 하나라도 비면 그 자리는 "좌표 미설정" 으로 남는다.

DB 제약 `ck_robot_location_pose` 가 이것을 강제한다. **셋 다 NULL 이거나 셋 다 값이 있어야
하며**, 그 사이는 없다. `yaw` 의 ±π 범위도 여기서 한 번 더 막는다. x 만 넣고 y 를 빠뜨린
행은 애초에 저장되지 않으므로, "좌표가 반쯤 들어간" 상태를 코드가 다룰 필요가 없다.

---

## 4. 좌표가 없으면 명령을 내리지 않는다

`RobotLocation.hasPose()` 가 세 값이 모두 있는지 본다.

```java
public boolean hasPose() {
    return poseX != null && poseY != null && poseYaw != null;
}
```

이동 명령을 만들 때 `DeviceCommandService.requireNavigableLocation()` 이 두 단계로 막는다.

| 상황 | 오류 |
|---|---|
| 그 종류의 위치가 등록되지 않음 | `ROBOT_LOCATION_NOT_FOUND` |
| 등록은 됐는데 좌표가 비어 있음 | `LOCATION_POSE_NOT_CONFIGURED` |

좌표 없이 이름만 보내면 로봇이 자기 파라미터의 좌표를 쓰게 되므로, 아예 발행하지 않는다.

---

## 5. 이동 명령이 나가는 경로

### 5.1 명령 페이로드

`NavigateCommandPayload` — 필드 이름이 그대로 JSON 키가 된다.

```
서버 → 젯슨   potner/device/<device_uid>/command/navigate
```

```json
{
  "destination": "WATER_STATION",
  "x": 1.250,
  "y": -0.480,
  "yaw": 1.5708,
  "requestId": "<uuid>"
}
```

`destination` 은 `RobotLocationType` 의 이름을 그대로 쓴다.

회신은 `potner/device/<device_uid>/result/navigate` 로 오며 `requestId` 를 그대로 반향해야
한다. QoS 1 이라 같은 명령이 두 번 도착할 수 있고, `requestId` 가 중복을 걸러내는 열쇠다.

### 5.2 발행까지의 단계

```
DeviceCommandService.issue()
  ├ 배정된 로봇 찾기          (없으면 PLANT_ASSIGNMENT_NOT_FOUND)
  ├ 명령 종류로 대상 장치 결정  (NAVIGATE → JETSON_ORIN)
  ├ requireNavigableLocation() ← robot_location 에서 좌표를 읽는다
  ├ device_command 행 저장
  └ DeviceCommandIssuedEvent 발행 (poseX/poseY/poseYaw 동봉)
        │  AFTER_COMMIT
        ▼
DeviceCommandPublishListener → MQTT 발행
```

커밋된 뒤에 발행한다. 명령 행이 저장되지 않았는데 로봇만 움직이는 상황을 막기 위해서다.

**명령 종류가 받을 장치를 정한다.** 펌프·팬·카메라는 스테이션의 라즈베리에, 바퀴는 젯슨에
붙어 있다.

| 명령 | 대상 장치 | 토픽 |
|---|---|---|
| `NAVIGATE` | `JETSON_ORIN` | `command/navigate` |
| `WATER` | `RASPBERRY_PI` | `command/water` |
| `FAN` | `RASPBERRY_PI` | `command/fan` |
| `CAPTURE` | `RASPBERRY_PI` | `command/capture` |

---

## 6. 좌표가 쓰이는 흐름 넷

### 6.1 자동 급수 — `AutoWateringOrchestrator`

토양 수분 부족을 감지하면 시작한다. 한 번에 한 단계씩, 앞 단계의 결과를 받아 다음을 낸다.

```
NAVIGATE(WATER_STATION) ─OK─▶ WATER ─OK─▶ FAN ─OK─▶ NAVIGATE(HOME)
```

체인을 잇는 조건은 `event.status().continuesChain()` 이다. `OK` 뿐 아니라 `SKIPPED` 도
잇는다 — 장치가 자기 판단으로 수행하지 않은 경우(이미 충분히 젖어 있는 등)에도 다음
단계로 가야 로봇이 스테이션에 남지 않는다.

### 6.2 주기 환기 — `AutoDryingScheduler`

```
NAVIGATE(WATER_STATION) ─OK─▶ FAN ─OK─▶ NAVIGATE(HOME)
```

**습도 알림이 조건이 아니다.** 예전에는 고습 알림이 열려 있는 동안에만 돌았는데, 그러면
습도가 정상인 날에는 하루 0회다. 공기 순환의 이득 절반은 습도와 무관하다 — 바람에 흔들린
줄기는 굵고 짧아지고, 잎 표면의 정체된 공기층이 걷혀야 증산이 이어진다.

지금은 낮 동안 일정한 간격으로 돈다. 조건이 다섯이고 싼 것부터 본다.

| 조건 | 기본값 |
|---|---|
| 로봇이 유휴인가 | `RobotBusyGuard` |
| 환기 시간대인가 | 8시 ~ 22시 |
| 마지막 송풍에서 간격이 지났나 | 9000초 (2.5시간) |
| 오늘 상한에 닿았나 | 6회 |
| 너무 춥지 않나 | 15℃ |

가장 필요한 한 번은 여기서 돌지 않는다. 급수 직후가 곰팡이에 가장 취약한데 그때 로봇은
이미 스테이션에 서 있으므로, 그 한 번은 §6.1 의 체인이 이동 없이 끼워 넣는다. 이쪽은 목적
구분 없이 마지막 `FAN` 시각을 보므로 곧바로 또 돌리지 않는다.

날짜 경계는 서비스 타임존이다. 상한 6회는 UTC 자정이 아니라 KST 자정에 초기화된다.

### 6.3 햇빛 자리 이동 — `SunlightRelocationScheduler`

하루 누적 광량이 기준에 못 미치면 `NAVIGATE(SUNLIGHT)` 를 낸다. 이미 햇빛 자리에 있으면
(마지막 `NAVIGATE` 의 목적지가 `SUNLIGHT` 이고 성공했으면) 다시 보내지 않는다.

### 6.4 마중 — `ArrivalService`

다른 셋과 달리 **`NAVIGATE` 를 쓰지 않는다.** 전용 명령이 따로 있다.

귀가 이벤트는 `POST /api/v1/arrival/events` 로 들어온다(앱의 임시 버튼 또는 Android
Geofence).

```
서버 → 젯슨   potner/device/<device_uid>/command/welcome_start
서버 → 젯슨   potner/device/<device_uid>/command/welcome_cancel
```

`welcome_start` 페이로드는 **마중 지점과 복귀 지점을 한 번에** 싣는다. 마중을 끝낸 뒤
돌아올 곳을 로봇이 다시 물어보지 않게 하기 위해서다.

```json
{
  "eventId": "...", "visitId": "...", "requestId": "...",
  "destination": "GREETING",
  "x": 2.840, "y": 1.120, "yaw": -0.7854,
  "returnDestination": "HOME",
  "returnX": 0.0, "returnY": 0.0, "returnYaw": 0.0,
  "waitSeconds": 120,
  "totalTimeoutSeconds": 300,
  "publishedAt": "2026-08-09T12:00:00Z"
}
```

`waitSeconds` 는 마중 지점에서 사용자를 기다리는 시간이고, `totalTimeoutSeconds` 는 마중
전체에 허용되는 시간이다. 각각 `ARRIVAL_WELCOME_WAIT_SECONDS`(기본 120),
`ARRIVAL_TOTAL_TIMEOUT_SECONDS`(기본 300) 로 조정한다.

`welcome_cancel` 은 복귀 좌표만 싣는다.

| 이벤트 | 필요한 좌표 |
|---|---|
| `APPROACH` | `GREETING` + `HOME` — 둘 다 좌표가 있어야 한다 |
| `CANCEL` | `HOME` 만 |

`eventId` 로 중복을 막고, `CANCEL` 은 같은 `visitId` 의 `APPROACH` 가 먼저 있어야 받는다
(없으면 `ARRIVAL_VISIT_NOT_FOUND`).

---

## 7. 물 부족 보고

`WATER_STATION` 만 물 부족 상태를 갖는다. 장치가 보고하면 `robot_location.water_low` 와
`water_low_at` 이 갱신된다.

알림은 **부족 상태로 처음 바뀔 때만** 나간다. 장치가 같은 상태를 주기적으로 반복 보고하므로
매번 알리면 물을 채울 때까지 알림이 쏟아진다. `water_low_at` 은 해제된 뒤에도 남겨 두어
언제까지 부족했는지가 보이게 한다.

---

## 8. 고칠 때 함께 봐야 하는 것

| 바꾸는 것 | 함께 고쳐야 하는 것 |
|---|---|
| `RobotLocationType` 에 종류 추가 | DB `CHECK` 제약, 앱의 등록 화면, 로봇 수신기 |
| `NavigateCommandPayload` 필드 | 로봇 파서, [DEVICE-MQTT.md](DEVICE-MQTT.md) |
| 좌표 단위·프레임 | 로봇의 `map` 프레임 기준과 반드시 같아야 한다 |
| 명령 이름(`commandName`) | MQTT 토픽 마지막 조각이므로 장치 구독 이름과 같아야 한다 |
| ACL 에 없는 토픽 추가 | `infra/mosquitto/config/acl` — 빠지면 발행이 **조용히 버려진다** |

마지막 항목이 특히 위험하다. Mosquitto 는 권한 없는 publish 를 조용히 버리고, 발행한 쪽은
성공한 줄 알며 서버 로그에도 아무것도 남지 않는다.
