# 루프 3 — MQTT 명령 왕복 (예측 → 시행 → 수정)

날짜: 2026-08-04 / 브랜치: `Raspberry-fix/full-simulation-debug` (Raspberry-develop 하위)

## 0. 이 루프를 도는 이유

루프 2 의 4절이 남긴 1순위 후보다. 지금까지 MQTT 는 **한 번도 왕복한 적이 없다**:

- 루프 1: `mqtt=off` 로만 돌았다.
- 루프 2: Q9 에서 "브로커가 죽었을 때 수집이 살아남는가"만 봤다 — 즉 **연결 실패 경로만** 봤다.
- 단위 테스트(`test_water_command.py`, `test_capture_command.py`)는 `handle_command()` 를 직접 부르거나
  네트워크를 monkeypatch 한다. **브로커를 통과하는 실제 수신→실행→회신은 검증된 적이 없다.**

그래서 "서버가 명령을 던지면 Pi 가 받아서 실행하고 결과를 돌려준다" 는 이 프로젝트의 핵심 흐름이
통째로 시뮬레이션 밖에 있었다. Pi 실기에서는 왕복을 확인했지만(`docs/potner-mqtt-pi-handoff.md`),
PC 에서 재현할 수단이 없어 회귀를 잡을 방법이 없다.

### 브로커를 어떻게 마련하나 (실 브로커 금지)

CLAUDE.md 불변식상 실 브로커(`i15e104.p.ssafy.io`)에는 붙지 않는다. 로컬에 mosquitto 도 없고
`amqtt` 같은 파이썬 브로커도 설치돼 있지 않다(설치는 환경을 건드리므로 배제). 따라서
**MQTT 3.1.1 최소 브로커를 scratchpad 에 직접 구현**해서 쓴다 — 설치 없이 자족적이고, 오가는 패킷을
전부 로그로 남길 수 있어 왕복 검증에 오히려 유리하다.

**위험**: 자작 브로커의 버그를 프로젝트 버그로 오인할 수 있다. 그래서 **W1 에서 알려진 정상 클라이언트
(paho)끼리 먼저 왕복시켜 브로커를 교정**하고, 그게 통과한 뒤에만 프로젝트 코드를 붙인다.
브로커는 시뮬레이션 도구이므로 레포에 커밋하지 않는다(scratchpad 에만 둔다).

### 시행 조건

- `mqtt.enabled: true`, `host: 127.0.0.1`, `port: 18830`(실 브로커 포트 1884 와 다르게)
- 리스너는 **`--once` 가 아닐 때만** 생성된다(`main.py:54,61`) → 연속 루프로 돌린다
- 두 리스너 모두 username/password 가 비면 `start()` 가 `ValueError` → `MQTT_PASSWORD` 를 넣는다
- 과급수 가드 상태를 깨끗이 두려고 `pump.safety.state_path` 를 scratchpad 로 돌린다
  (루프 2 에서 급수 기록이 남아 첫 명령부터 TOO_SOON 이 날 수 있으므로)

## 1. 시행 전 예측

| # | 시행 | 예측 결과 |
|---|---|---|
| W1 | 자작 브로커 교정: paho → paho, QoS1 왕복 | 발행한 메시지가 구독자에게 그대로 도착. **여기서 실패하면 브로커 문제이지 프로젝트 문제가 아니다** — 통과할 때까지 브로커만 고친다 |
| W2 | 연속 루프 기동 | 배너에 `mqtt=on 127.0.0.1:18830 hb=30s`, `water=on ... pump=MockWaterPump`, `capture=on ...`. 브로커에 **CONNECT 3건**(telemetry publisher / `{id}-water` / `{id}-capture`) + **SUBSCRIBE 2건**(command/water, command/capture) |
| W3 | 텔레메트리 | `interval_sec` 마다 `sensor/telemetry` 로 발행 |
| W4 | 하트비트 | `status/heartbeat` 발행. **불확실**: connect 직후 1회 발행 후 30초 주기일 것으로 보지만, 첫 발행이 30초 뒤일 수도 있다 |
| W5 | 급수 명령 `{"ml":50,"requestId":"w-ok"}` | `result/water` 에 `status:"OK"`, `requestedMl:50`, `dispensedMl:50`, `durationSec:2.0`, `requestId:"w-ok"` 반향, `messageId`/`deviceId`/`measuredAt` 포함 |
| W6 | W5 직후 같은 명령 재전송 | `status:"SKIPPED"`, `skipCode:"TOO_SOON"`, `dispensedMl:0.0` (min_interval_sec 300) |
| W7 | `{"requestId":"w-err"}` (ml 없음) | `status:"ERROR"`, `error` 에 `invalid ml` 문구 |
| W8 | 비-JSON 문자열 발행 | `status:"ERROR"`, `error` 에 `invalid payload` 문구, `requestId` 없음 |
| W9 | 급수 2초 진행 중 두 번째 명령 | `status:"BUSY"`. 근거: `handle_command` 가 `_dispense_lock` 을 **guard 검사보다 먼저** 잡으므로(155-168행) TOO_SOON 보다 BUSY 가 우선한다 |
| W10 | 촬영 명령 `{"requestId":"c-ok"}` | `result/capture` 에 성공 회신 + `data/camera` 에 파일 저장. `upload.enabled: false` 라 업로드는 스킵 |
| W11 | 수위: `mock_values` 로 "물 없음" 유지 | `status/water-low` 로 발행. `hold_sec:10` 경과 **전에는 확정 전 값**, 경과 후 `waterLow:true`. **불확실**: 확정 이전에 무엇을 발행하는지(초기 확정값이 없을 때 발행을 거르는지 `false` 를 보내는지)는 예측이 안 선다 |

불변식 준수: 실 브로커·실 GMS·실 하드웨어 미접촉. 전부 `127.0.0.1` + `platform: mock`.

## 2. 시행 결과 (예측 대조)

| # | 실제 결과 | 예측 대조 |
|---|---|---|
| W1 | `PASS: QoS1 왕복 정상` — 프로젝트의 `src/mqtt/client.py::publish_json` 으로 발행한 것이 구독자에 그대로 도착 | 일치. 이후 결과를 브로커 탓으로 돌릴 수 없음이 확보됐다 |
| W2 | 배너 `mqtt=on 127.0.0.1:18830 hb=30s water=on …/command/water pump=MockWaterPump capture=on …/command/capture`. 브로커에 **CONNECT 3건**(`raspberry-01-collector`/`-water`/`-capture`) + **SUBSCRIBE 2건** | 일치 (client_id 3종 분리까지 예측대로) |
| W3 | `sensor/telemetry` 로 **주기당 2건** 발행 — `TEMPERATURE/CELSIUS` + `HUMIDITY/PERCENT` 별도 메시지. 수집 로그의 `mqtt=ok(2)` 가 이것 | 일치 (2건인 건 예측에 없던 세부, 서버 규약대로) |
| W4 | 30초 주기, connect 직후 1회 발행 후 반복 (79초 관측에 3건) | **불확실로 뒀던 예측이 맞은 쪽으로 확정** |
| W5 | `status:"OK"`, `requestedMl:50.0`, `dispensedMl:50.0`, `durationSec:2.0`, `requestId:"w-ok"` 반향, `messageId`/`deviceId`/`measuredAt` 포함 | 일치. **PC 에서 명령 왕복이 처음으로 성립** |
| W6 | `status:"SKIPPED"`, `skipCode:"TOO_SOON"`, `dispensedMl:0.0`, `reason:"직전 급수 후 15초밖에…"` | 일치 |
| W7 | `status:"ERROR"`, `error:"invalid ml: {'requestId': 'w-err'}"` | 일치 |
| W8 | `status:"ERROR"`, `error:"invalid payload: Expecting value…"`, `requestId` 키 없음 | 일치 |
| W9 | **예측 빗나감.** 두 명령 모두 `OK`/`dispensedMl:50.0`, 회신 간격(0.404s)이 전송 간격과 동일 → 직렬화가 아니라 둘 다 즉시 완료. 원인: `MockWaterPump._run()` 이 **"실제 대기 없이 가동 이력만 기록"**(`src/actuators/mock.py:11`) 하므로 락이 마이크로초만 유지된다 | **코드 버그 아님 — 예측의 전제가 틀렸다.** BUSY 로직 자체는 `tests/test_water_command.py:49::test_handle_command_busy_when_lock_held` 가 락을 직접 잡아 이미 검증한다. mock 은 시간을 모델링하지 않으므로 **타이밍 의존 경로는 원리적으로 E2E 재현 불가** |
| W10 | `result/capture` 에 `status:"OK"`, `requestId:"c-ok"` 반향, `attempts:1`, `quality`(mean 127.0, ok) + `path`/`fileName`/`driver`/`width`/`height`, 파일 저장됨. `upload.enabled:false` 라 업로드 필드 없음 | 일치 |
| W11 | `hold_sec:10` 대로 동작 — 물없음 전환 후 **10초 뒤 첫 `waterLow:true`**, 이후 매 주기 재발행(멱등). **확정 전에는 아무것도 발행하지 않는다**(`false` 를 보내지도 않음) | 일치. **불확실로 뒀던 "확정 전 무엇을 발행하나"는 "발행 안 함" 으로 확정** |

### 추가 확인 — 복구 방향 (`waterLow:false`)

인계 문서(`docs/water-level-sensor-handoff.md`)가 **"`false` 를 안 보내면 물 보충 후에도 서버 플래그가
영원히 안 내려가 재알림이 막힌다"** 고 경고한 부분이다. 루프 2 에서 넣은 `mock_values` 덕분에 이번에
처음으로 PC 에서 관측할 수 있었다: 물없음 15초 유지 → `waterLow:true` 확정 → 물있음 복구 →
약 10초 뒤 `waterLow:false` 로 전환 → **이후 매 주기 `false` 재발행**. 규약대로 동작한다.

**요약**: 예측 11건 중 **10건 적중, 1건 빗나감(W9 — 예측의 전제가 틀린 것이지 코드 버그가 아님)**.
**이번 루프에서 발견된 코드 버그는 0건이다.** MQTT 명령 계층은 왕복 전 구간(정상/게이트/오류/촬영/수위)
에서 규약대로 동작했다. 루프 2 와 달리 고칠 게 없었다는 것 자체가 결과다 — 없는 버그를 만들지 않았다.

### 시행 중 알게 된 것 (프로세스)

- **루프 2 의 수집기가 17분간 좀비로 살아 있었다.** 루프 2 Q6 에서 `TaskStop` 으로 죽였다고 여겼지만,
  실제로는 bash 셸만 죽고 자식 `python.exe`(PID 5076, `sim_water.yaml`)는 계속 돌고 있었다 — 루프 2 Q8
  에서 관찰한 "MSYS2 → native Windows python 에 신호가 전달되지 않는다" 와 같은 원인이다.
  MQTT 가 꺼진 설정이라 이번 브로커 로그는 오염시키지 않았지만(CONNECT 목록으로 확인), `data/readings.csv`
  에는 두 프로세스의 행이 섞여 들어갔다. **다음부터 백그라운드 수집기는 `Get-CimInstance Win32_Process`
  로 PID 를 확인해 `Stop-Process` 로 끊을 것** — `TaskStop`/`timeout` 을 신뢰하면 안 된다.
- 같은 이유로 시뮬레이션 중 수집기 2개가 동시에 붙어 client_id 가 겹친 구간이 있었다(자작 브로커는
  중복 client_id 를 강제로 끊지 않는다). 관측 구간을 나눌 때는 프로세스가 하나뿐인지 먼저 확인해야 한다.

## 3. 수정/삭제/추가

### 추가 — `scripts/mini_mqtt_broker.py`

**1절에서는 "브로커는 scratchpad 에만 두고 커밋하지 않는다" 고 적었으나 판단을 바꿨다.** 돌려보니
이것이 PC 에서 MQTT 왕복을 검증할 **유일한 수단**이었고, scratchpad 는 세션이 끝나면 사라져
다음 루프가 같은 것을 처음부터 다시 만들어야 하기 때문이다. `scripts/` 의 기존 개발·운영 헬퍼
(`publish_climate_mqtt.py` 등) 옆에 두되, docstring 에 **개발 도구이며 인증·ACL 이 없으니 운영/테스트
경로에 연결하지 말 것**을 명시했다. 표준 라이브러리만 쓰므로 의존성은 늘지 않는다.

### 코드 수정 없음

발견된 버그가 없어 `src/` 변경이 없다. W9 도 코드가 아니라 예측 전제의 문제였고, BUSY 로직은 이미
단위 테스트가 덮고 있어 손대지 않았다. **mock 펌프에 "실제로 대기하는" 옵션을 넣을까 검토했으나
하지 않았다** — 대기 없는 것은 테스트를 빠르게 하려는 의도된 설계(docstring 에 명시)이고, BUSY 는
이미 검증돼 있어 새 손잡이를 넣을 근거가 약하다. 루프 2 에서 `mock_values` 를 넣은 것과는 상황이
다르다(그건 기능 전체가 관측 불가였고, mock 클래스가 이미 지원하는 걸 배선만 안 한 경우였다).

### 테스트

이번 루프로 추가/변경된 테스트 없음. 기존 스위트 **348 passed, 2 skipped** 유지, flake8 `E9,F63,F7,F82` 0건.

## 4. 다음 루프 후보

- **이 왕복을 자동화된 통합 테스트로** — 지금은 브로커를 손으로 띄우고 프로브를 손으로 쏜다.
  `scripts/mini_mqtt_broker.py` 를 pytest 픽스처(빈 포트 자동 할당 + 종료 정리)로 감싸면
  명령 왕복이 회귀 테스트가 된다. 단위 테스트가 monkeypatch 로 건너뛰는 구간을 실제로 덮는 유일한 길.
- **`upload.enabled: true` 경로** — 이번엔 업로드가 꺼진 채로만 촬영 왕복을 봤다. 로컬 더미 HTTP 서버로
  201/409/네트워크 실패를 태우면 "409 는 성공", "업로드 실패가 촬영을 ERROR 로 바꾸지 않는다" 는
  두 불변식을 E2E 로 확인할 수 있다.
- **재접속 시 구독 유지** — `_on_connect` 가 재접속마다 `subscribe` 를 다시 걸도록 돼 있는데
  (`water_command.py:118-119`), 브로커를 죽였다 살렸을 때 실제로 명령을 다시 받는지는 안 봤다.
