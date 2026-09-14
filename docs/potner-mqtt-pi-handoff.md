# Potner 라즈베리파이 MQTT 연동 — 이어하기 메모

> 작성일: 2026-07-30, 갱신: 2026-08-03  
> 브랜치: `Raspberry-feature/watering-volume-calculation/55`  
> 실행 위치: `~/S15P11E104` (루트). `sensor-collector/` 하위 폴더는 구조 통합 이전 잔재 — 무시.

문서와 코드가 어긋나면 **코드가 기준**. 규약·실패 해석은 「Potner 장치 연동 가이드」 7절을 따른다.

---

## 한줄 요약

**ACL 문제는 해결됐다 (2026-08-03 실측).** 서버 촬영 명령 수신 → 촬영 → 사진 업로드(HTTP 201,
같은 날 재촬영은 409=정상) → `result/capture` OK 회신까지 전 구간 왕복 검증 완료.  
남은 것: 급수 명령 실왕복(12V 전원 + 유량 보정 선행), 노출 보정값 실측 확정.

---

## 서버 / 장치 식별

| 항목 | 값 |
|------|-----|
| 브로커 | `i15e104.p.ssafy.io:1884` |
| MQTT 계정 / deviceId | `raspberry-01` |
| 앱 계정 | `fff@naver.com` |
| robotId | `59d829a7-2f0f-4842-ae4c-de41e1fe4fa5` |
| plantId | `4e20f51f-b9b4-4cf2-90ee-233ad4cf2950` ("로지") |
| 등록 | 2026-07-28 완료 — 재등록 불필요 |

토픽 (요지):

- 텔레메트리: `potner/device/raspberry-01/sensor/telemetry`
- 하트비트: `potner/device/raspberry-01/status/heartbeat` (30초)
- 물부족 보고: `…/status/water-low` — `{messageId, deviceId, waterLow(bool), measuredAt}`,
  false 도 매 주기 발행 (서버 `WaterLowMessage`, Server-develop `d4f9219`)
- 급수 명령/결과: `…/command/water`, `…/result/water`
- 촬영 명령/결과: `…/command/capture`, `…/result/capture`
- 팬 명령/결과: `…/command/fan`, `…/result/fan` — **앱에는 송풍 버튼 하나만 있으므로 풍량·시간은
  기기가 정한다.** 서버 실규약은 `{"seconds": N, "requestId": "…"}` 인데(2026-08-05 실측, 풍량은
  안 보낸다) 그 값들은 **쓰지 않고** `requestId` 만 결과에 되돌려준다. 명령이 오면 항상
  `fan.blow_speed_pct`(100%) 로 `fan.blow_run_sec`(10초) 만큼 돌고 자동 정지
  (`src/mqtt/fan_command.py`). 회신은 `status` + `appliedSpeedPct` + `durationSec` + `requestId`.
  - 풍량·시간 변경은 코드가 아니라 `config/raspberry_pi.yaml` 의 저 두 줄 → 배포 → 서비스 재시작.
  - **풍량은 100% 고정** (`blow_speed_pct: 100`). duty 를 낮추면 기동 토크가 부족해 팬이
    진동만 하고 돌지 않는다 (2026-08-05 실측: 70% = 진동만, 100% = 회전). 100% 로 기동한 뒤
    감속하는 방식도 70/85/95 로 시도했지만 100% 고정이 가장 안정적이어서 풍량 조절 기능
    자체를 걷어냈다. 다시 필요하면 `Fan.run_for` 에 기동 구간을 넣는 방식이었다는 것만
    기억할 것 (`git log --grep 기동` — `17e919e`, `ab7bb5c`).
  - 가동 중 재요청은 `BUSY` 로 회신하고 무시한다 (버튼 연타 방지).
  - 초기에 `speed` 를 필수로 요구했다가 서버 명령이 전부 `ERROR: invalid speed` 로 되돌아간 적
    있음 — 그 `error` 필드는 **앱 화면에까지 노출된다**(회신 경로가 앱까지 살아있다는 증거).

(결과 토픽은 서버 규약에 맞춰 `…/result/*` 로 변경됨 — `55b35be`. 단일 출처는
`config/raspberry_pi.yaml` `mqtt:` 섹션.)

client_id: `raspberry-01-collector` / `-water` / `-capture` / `-fan` (서버 `potner-backend-prod` 충돌 방지).

---

## 이미 끝난 것 (Pi)

1. **하트비트** — 30초 주기, 서버 devices API에서 ONLINE + `lastSeenAt` 갱신 실측.
2. **deviceId** — yaml에 `raspberry-01` 고정.  
   ⚠️ `DEVICE_ID` 환경변수가 있으면 보드 시리얼로 덮어써 서버가 폐기함 → 실행 전 `unset DEVICE_ID`.
3. **촬영 왕복 (2026-08-03 실측)** — 서버 명령 수신 → 촬영 → 업로드
   `HTTP 201 photoId=9e8ab397-…` → `result/capture` OK. 같은 날 2번째 명령은
   `HTTP 409 PHOTO_ALREADY_EXISTS_FOR_DATE` 를 성공으로 처리 (설계 의도, `upload.success_status`).
4. **급수 리스너** — `src/mqtt/water_command.py` + 과급수 방지 4축 게이트. 코드·테스트 완료,
   실왕복은 12V 전원 + 유량 보정 후.
5. **밝기 검사** — `on_failure: warn` (경고만, 차단 아님 — ERROR 회신이 서버 촬영 체인을 끊음).

---

## 해결됨 — 브로커 ACL (기록)

2026-07-30 시점 `raspberry-01` 계정의 모든 토픽 read가 막혀 있었다 (SUBACK Granted 인데
메시지 미전달 — Mosquitto ACL 거부는 이렇게 **조용히** 실패한다). 인프라 쪽 ACL 배포 후
2026-08-03 촬영 명령 수신으로 해소 확인.

교훈: 명령 리스너가 조용하면 코드부터 의심하지 말고 **왕복 publish 테스트**로 브로커 권한을
먼저 확인할 것 (`scripts/publish_climate_mqtt.py` 참고).

---

## 남은 일

1. **급수 실왕복** — 12V 전원 연결 → `cli/water.py calibrate [--apply]` 유량 보정 →
   서버 급수 명령 → 펌프 가동 → `result/water` 확인.
2. **노출 확정** — `exposure_value: 0.3` 은 잠정값. 서비스 재시작 후
   `journalctl -u sensor-collector | grep 밝기` 로 평균 120~180 확인, 220 이상이면 더 낮춤.
3. ~~서버 규약 확인 2건~~ — **2026-08-04 조사 완료.** 아래 "서버 규약 조사 결과" 절 참고.
   촬영은 문제없고, **급수 `SKIPPED` 는 서버가 못 받는다(미해결 이슈로 승격)**.
4. 실패 시 **가이드 7절 로그 문구 표**로 단계 특정 후 보고 (추측 금지).

### 서버 규약 조사 결과 (2026-08-04)

`origin/Server-develop` tip `1c4feba` 소스를 직접 읽어 확인했다. 서버 회신 스키마는
`command/dto/CommandResultMessage.java` 이고, **javadoc 이 "장치가 회신하는 명령 결과다.
라즈베리 구현이 기준이며 서버가 그 형식을 따른다" 고 명시**한다 — 규약의 기준은 우리 쪽이다.

**촬영 — 문제 없음.** `@JsonIgnoreProperties(ignoreUnknown = true)` 라 우리가 보내는
`attempts`·`quality`·`fileName` 처럼 스키마에 없는 필드는 **오류가 아니라 그냥 무시**된다.
`IMAGE_*` 는 스키마의 `code` 필드로 수용되고, `DeviceCommandResultService::errorMessage()` 가
`code: error` 형태로 앞에 붙여 진단에 쓴다. 장치 쪽 변경 필요 없음.

**급수 `SKIPPED` — 불일치. 서버가 받지 못한다.**

- `command/domain/DeviceCommandStatus.java::fromDeviceReport()` 의 switch 는
  `"OK"`/`"ERROR"`/`"BUSY"` **3개만** 처리하고 나머지는 `Optional.empty()` 를 돌려준다
  (javadoc: "장치가 모르는 값을 보내면 비어 있다 — 조용히 넘기지 않고 걸러낸다").
- `command/application/DeviceCommandResultService.java` 67~70행이 그걸 받아
  `CommandResultApplyOutcome.INVALID_STATUS` 로 **조기 반환**한다.
- 그 결과 `command.applyResult(...)` 가 실행되지 않아 명령이 **비종료 상태로 남고**,
  이어지는 `DeviceCommandCompletedEvent` 도 발행되지 않는다. 서비스 주석에 그 이벤트가
  "자동 케어 체인이 다음 단계를 잇는 근거" 라고 적혀 있으므로, **자동 케어 체인이 그 지점에서 끊긴다.**

우리 장치는 과급수 가드(`src/actuators/safety.py::WateringGuard`)가 막을 때마다 `SKIPPED` +
`skipCode`(`TOO_SOON` 등)를 보낸다 — 추측이 아니라 `docs/loops/LOOP_3.md` **W6 에서 실제
브로커 왕복으로 실측**한 페이로드다. 즉 서버가 급수 명령을 냈는데 마지막 급수로부터 5분이
안 지났으면, 지금 구조에서는 그 명령이 서버에서 영원히 안 끝난 채로 남는다.

**조치 방향 (미결 — 서버팀 협의 필요)**: 규약 기준이 장치이고 "가드가 막음" 은 실패(`ERROR`)와
의미가 다르므로, `SKIPPED` 를 `ERROR` 로 낮춰 우회하지 **않는다**. 서버 enum 에 `SKIPPED` 를
종료 상태로 추가하는 것이 맞는 방향으로 보이나, 서버 도메인 결정이라 이 브랜치에서 코드를
바꾸지 않았다. 협의 전까지는 **급수 명령이 가드에 걸리면 서버 쪽 명령이 미종료로 남는다**는 것을
알고 있을 것.

### 기대 시작 배너

```text
platform=raspberry_pi device_id=raspberry-01 … mqtt=on i15e104.p.ssafy.io:1884 hb=30s
water=on potner/device/raspberry-01/command/water
capture=on potner/device/raspberry-01/command/capture
fan=on potner/device/raspberry-01/command/fan fan=MosfetFan
```

### 기동 방법

```bash
cd ~/S15P11E104
unset DEVICE_ID
# .env 에 MQTT_PASSWORD (루트; config.py가 cwd·main.py 위치만 로드)
source .venv/bin/activate   # 없으면: python3 -m venv --system-site-packages .venv && pip install -r requirements.txt
# picamera2 는 apt(python3-picamera2) + venv --system-site-packages 필요
./scripts/run_pi_collector.sh
# 또는
.venv/bin/python -u main.py --config config/raspberry_pi.yaml
```

Cursor Agent 샌드박스에서는 DNS/GPIO/카메라 `/dev`가 막힐 수 있음 → **호스트 터미널**에서 실행.

온라인 확인: `GET /api/v1/plants/{plantId}/devices` → `raspberry-01` ONLINE, `lastSeenAt` ~30초 갱신.

---

## (기록) 2026-07-30 의 미커밋 변경분

전부 커밋 반영 완료 — 조도·토양 비활성, ILLUMINANCE/SOIL 발행 제거, Picamera2 lazy-init,
`scripts/run_pi_collector.sh`. 현재 PC·Pi 양쪽 워킹트리 clean.

I2C `Remote I/O error`(0x23, 0x48)는 **배선/전원/주소** 이슈로 판단 — 소프트웨어 우선순위 낮음.

---

## 주요 코드 위치

| 역할 | 경로 |
|------|------|
| 엔트리 | `main.py` |
| 설정 | `config/raspberry_pi.yaml`, `.env` (`MQTT_PASSWORD`) |
| 텔레메트리+HB | `src/mqtt/publisher.py` |
| 급수 | `src/mqtt/water_command.py` |
| 촬영 | `src/mqtt/capture_command.py` |
| 카메라 | `src/vision/camera.py` |
| 서버 ACL(참고) | `Server-develop` → `infra/mosquitto/config/acl` |

---

## 체크리스트 (다음 세션)

- [x] 인프라: ACL 배포·리로드 — 해결 확인 (2026-08-03)
- [x] 서버 촬영 명령 → 사진 + 업로드(201/409) + `result/capture` OK
- [ ] Pi 서비스 재시작 (`sudo systemctl restart sensor-collector`) — `on_failure: warn` +
      `exposure_value: 0.3` 반영. 기동 시각이 config 커밋보다 이후인지 확인
- [ ] 노출 실측: `journalctl -u sensor-collector | grep 밝기` 평균 120~180 확인
- [ ] 12V 연결 → `cli/water.py calibrate --apply` → 서버 급수 명령 → 펌프 + `result/water`
- [ ] 서버 팀 규약 확인: 급수 `SKIPPED` 상태 / 촬영 `attempts`·`IMAGE_*` 코드
- [ ] 안전 임계값(500ml/300s/1000ml/70%) 팀 합의
- [ ] 실패 시 가이드 7절 표로 단계 보고
- [ ] (여유 시) 조도/토양 I2C 하드웨어 점검
