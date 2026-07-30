# Potner 라즈베리파이 MQTT 연동 — 이어하기 메모

> 작성일: 2026-07-30  
> 브랜치: `Raspberry-develop` @ `2eed1ae`  
> 실행 위치: `~/S15P11E104` (루트). `sensor-collector/` 하위 폴더는 구조 통합 이전 잔재 — 무시.

문서와 코드가 어긋나면 **코드가 기준**. 규약·실패 해석은 「Potner 장치 연동 가이드」 7절을 따른다.

---

## 한줄 요약

Pi 쪽 코드·기동은 끝났다. **공용 브로커 ACL(read)** 때문에 급수/촬영 명령이 장치에 안 내려온다.  
인프라가 ACL을 배포·리로드한 뒤, 서버가 실명령을 보내면 이 Pi에서 왕복만 검증하면 된다.

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
- 급수 명령/결과: `…/command/water`, `…/command/water/result`
- 촬영 명령/결과: `…/command/capture`, `…/command/capture/result`

client_id: `raspberry-01-collector` / `-water` / `-capture` (서버 `potner-backend-prod` 충돌 방지).

---

## 이미 끝난 것 (Pi)

1. **하트비트** — 30초 주기, 서버 devices API에서 ONLINE + `lastSeenAt` 갱신 실측.
2. **deviceId** — yaml에 `raspberry-01` 고정.  
   ⚠️ `DEVICE_ID` 환경변수가 있으면 보드 시리얼로 덮어써 서버가 폐기함 → 실행 전 `unset DEVICE_ID`.
3. **급수 리스너** — `src/mqtt/water_command.py` (코드 완료, 실브로커 왕복 미검증).
4. **촬영 리스너** — `src/mqtt/capture_command.py` + `camera.enabled: true` (코드 완료, 실브로커 왕복 미검증).
5. **collector 기동 확인 (2026-07-30)**  
   배너: `mqtt=on … hb=30s water=on … capture=on …`  
   루프: `mqtt=ok(2)`.

---

## 막힌 것 — 브로커 ACL (인프라)

### 증상 (실측)

- `raspberry-01` 계정으로 **모든 토픽 read가 막힘**.
- SUBACK는 Granted로 오지만 **메시지가 전달되지 않음**.
- 그래서 급수/촬영 리스너는 떠 있어도 명령을 못 받음.

### 저장소 vs 배포

- `Server-develop`의 `infra/mosquitto/config/acl`에는 이미  
  `pattern read potner/device/%u/command/#` 가 있음.
- 저장소 정의는 괜찮아 보이므로, **공용 서버에 올라간 ACL/password가 다르거나 브로커가 리로드되지 않은 상태**일 가능성이 큼.
- **Pi 코드로 고칠 문제 아님.**

### 인프라 담당자 전달용

```
계정: raspberry-01
증상: SUBACK Granted but no message delivery on potner/device/raspberry-01/command/#
확인: 서버의 실제 mosquitto ACL/password 파일이
      Server-develop infra/mosquitto/config/acl 과 일치하는지,
      배포 후 mosquitto reload/restart 했는지
기대: pattern read potner/device/%u/command/# 가 raspberry-01 에 적용
```

---

## ACL 풀린 뒤 Pi에서 할 일

1. collector가 이 Pi에서만 떠 있는지 확인 (개발 PC 등에서 `raspberry-01` 중복 구독 금지).
2. 서버 담당자가 급수 / 촬영 실명령 전송.
3. Pi 로그에서 반응 확인:
   - 급수: `[water] …` → 펌프 가동 → `…/water/result`
   - 촬영: `[capture] 촬영 명령 수신` → 파일 저장 → `…/capture/result` (`OK` / `ERROR(code)` / `BUSY`)
4. 실패 시 **가이드 7절 로그 문구 표**로 단계 특정 후 보고 (추측 금지).

### 기대 시작 배너

```text
platform=raspberry_pi device_id=raspberry-01 … mqtt=on i15e104.p.ssafy.io:1884 hb=30s
water=on potner/device/raspberry-01/command/water
capture=on potner/device/raspberry-01/command/capture
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

## 로컬 미커밋 변경 (2026-07-30 작업 잔여)

커밋하지 않은 WIP. 이어할 때 `git status` / `git diff`로 재확인.

| 파일 | 내용 |
|------|------|
| `config/raspberry_pi.yaml`, `config/default.yaml` | 조도(bh1750)·토양(ads1115) `enabled: false` — Pi 담당 아님 / I2C 실패 노이즈 제거 |
| `src/mqtt/publisher.py`, `tests/…`, `main.py` | ILLUMINANCE·SOIL_MOISTURE 발행·로그 제거에 맞춤 |
| `src/sensors/factory.py` | 센서 팩토리 쪽 동일 취지 |
| `src/vision/camera.py` | Picamera2 **lazy-init** (첫 촬영 때 오픈) — 기동 시 카메라 없어도 mqtt/hb/water/capture 기동 가능 |
| `scripts/run_pi_collector.sh` | `unset DEVICE_ID` + `.env` + main 실행 헬퍼 (untracked) |

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

- [ ] `git pull` on `Raspberry-develop` (Agent에선 lab.ssafy.com이 막힐 수 있음 → 호스트에서)
- [ ] `unset DEVICE_ID`, collector 기동, 배너 `mqtt/water/capture=on` 확인
- [ ] 다른 곳에서 `raspberry-01` 리스너 없는지 확인
- [ ] 인프라: ACL 배포·리로드 완료 여부 확인
- [ ] 서버 급수 명령 → 펌프 + result
- [ ] 서버 촬영 명령 → 사진 + result (`OK`/`ERROR`/`BUSY`)
- [ ] 실패 시 가이드 7절 표로 단계 보고
- [ ] (여유 시) 조도/토양 I2C 하드웨어 점검 또는 WIP 커밋 여부 결정
