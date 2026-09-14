# 수위 센서(플로트 스위치) 작업 정리 — 이어하기 메모

> 작성일: 2026-08-03
> 브랜치: `Raspberry-feature/watering-volume-calculation/55` (`Raspberry-develop` 에 머지 완료: `101e55a`, `3a14334`)
> 관련 하드웨어: devicemart 1346031 — 5CFS-YZ-1 (NO타입) 플로트 스위치, 급수대 물리핀 9(GND)+13(GPIO27)

문서와 코드가 어긋나면 **코드가 기준**. Pi/MQTT 공통 사항(브로커 주소, deviceId, 토큰 등)은
`docs/potner-mqtt-pi-handoff.md` 를 따른다 — 이 문서는 수위 센서 기능 자체만 다룬다.

---

## 한줄 요약

급수대 수위 센서 부착 → 동작 확인 CLI → 극성/노이즈 실측 → 서버 실규약(전용 상태 토픽) 연동 →
10초 유지 게이트까지, 티켓 전 구간 구현·Pi 실측·커밋/머지 완료. 남은 건 물받이(두 번째 센서)와
급수 안전 게이트 연동 — 아래 "남은 일" 참고.

---

## 완료한 것

1. **드라이버** `src/sensors/water_level.py` — `FloatSwitchSensor`/`MockFloatSwitchSensor`.
   `read()` 1회 읽기, `read_stable()` 다수결(기본 5표, 0.06초 간격)로 뜨개 출렁임 흡수.
   `DEFAULT_PIN=27`, `DEFAULT_INVERT=True`(실측: 접점 닫힘=물 없음).
2. **CLI** `cli/water_level.py` — `read`/`watch`. collector·MQTT·다른 센서 import 없이 완전 독립 실행
   (GPIO27 만 사용, 펌프 17/18·팬 23·DHT 4·I2C 와 안 겹침). `watch` 는 접점이 바뀌는 즉시 출력한다 —
   처음엔 "감지/확정/취소" 유지시간 필터를 넣었었지만, 실물 테스트 결과 리드스위치가 물에 완전히
   잠겼을 때는 안정적으로 반응해 필터가 불필요하다고 판단해 **제거**했다(단순 즉시 표시로 복귀).
3. **수집 루프 통합** `src/collector.py`/`src/sensors/factory.py` — `read_stable()` 로 매 주기 읽어
   `row["water_present"]` 기록, `CsvStorage` 에도 컬럼 추가.
4. **서버 보고 — 실규약 확인 후 재구현**: 처음엔 텔레메트리(`sensorType: WATER_LEVEL`)로 추측 구현했으나,
   실제 서버 코드(`Server-develop` `d4f9219`, `WaterLowMessage`/`StationWaterLowService`)를 확인해 보니
   **전용 상태 토픽**(`potner/device/{id}/status/water-low`, 페이로드 `{messageId, deviceId, waterLow, measuredAt}`)
   규약이었다 — 텔레메트리 코드를 걷어내고 이 규약으로 교체(`src/mqtt/payloads.py::water_low_payload`,
   `src/mqtt/publisher.py`). `waterLow=false` 도 매 주기 발행해야 한다 — 서버 `RobotLocation.reportWaterLow`
   가 매번 값을 그대로 덮어쓰므로, false 를 안 보내면 물 보충 후에도 서버 플래그가 영원히 안 내려가
   재알림이 막힌다(`WaterLowMessage.java` 주석에도 명시).
5. **10초 유지 게이트** `src/mqtt/publisher.py::WaterLowHoldFilter` — 수위 신호가
   `sensors.water_level.hold_sec`(기본 10초) 이상 **연속** 유지되어야 확정해서 서버로 발행한다.
   짧은 잔떨림은 확정 전에 원상복귀하면 무시된다. 확정 후에는 매 주기 그대로 재발행(멱등),
   센서 읽기 실패(None) 시에는 마지막 확정값을 유지한다.
6. **Pi 실측(2026-08-03)** — 실제 `sensor-collector` 서비스 로그로 게이트 타이밍 검증:

   ```
   17:13:00 water_present=False 로 전환 (후보 등록)
   17:13:10 10초 경과 → 확정, waterLow:true 발행 시작
   17:13:16/21 계속 물 없음 → 매 주기 재발행(멱등)
   17:13:26 water_present=True 로 복귀 (새 후보)
   17:13:37 11초 경과 → 재확정, waterLow:false 로 복귀
   ```
7. **테스트** — `tests/test_water_level.py`(드라이버·CLI, 17개), `tests/test_water_level_telemetry.py`
   (수집 루프·MQTT 계약·10초 게이트, 20개+). 전체 스위트 341개(339 pass, 2 skip) 통과.
8. **커밋/머지** — `Raspberry-feature/watering-volume-calculation/55` → `Raspberry-develop` 2회 머지
   (`101e55a` 초기 통합, `3a14334` 10초 게이트 추가), 둘 다 원격 푸시 완료.

---

## 트러블슈팅 기록 (재발 시 먼저 확인)

- **`GPIO busy`**: `sensor-collector` 서비스가 `sensors.water_level.enabled: true` 로 GPIO27 을 이미
  물고 있으면 `cli/water_level` 단독 실행이 이 에러로 실패한다. 단독 테스트 전엔
  `sudo systemctl stop sensor-collector`, 끝나면 `sudo systemctl start sensor-collector`.
- **`sudo: a password is required`**: 원격 ssh 비대화형 세션은 sudo 캐시가 만료되면 비밀번호를
  대신 입력할 방법이 없다 — 사용자가 Pi에서 직접 한 번 sudo 명령을 실행해 캐시를 갱신해야
  이어서 원격으로 서비스 재시작/등이 가능하다.
- **반응이 계속 없을 때**: 코드/타이밍보다 **배선(물리 접촉 단자)부터 의심**할 것 — 이번에도
  결국 실제로 선이 빠져 있던 게 원인이었다. 독립 CLI(`watch`)로 raw 값이 실제로 바뀌는지부터
  재확인하고, 안 바뀌면 소프트웨어 문제가 아니다.
- **실시간 물리 테스트 동기화**: 대화 턴 사이 지연 때문에 "지금 시작" 류의 초 단위 타이밍 cue 는
  자주 어긋난다. 90~150초의 넉넉한 캡처 창을 주고 자유롭게 여러 번 테스트하게 하는 편이 안전하다.
  실제 서버 발행값(`waterLow`)은 ACL 상 device 계정으로 재구독이 안 되므로(자기 발행 토픽 read
  권한 없음), `journalctl`의 raw `water_present` 타임스탬프 + 10초 규칙을 손으로 계산해 검증했다.

---

## 남은 일 / 확인 필요

- [x] ~~**물받이(두 번째 수위 센서) 미설치**~~ — **폐기(2026-08-04 확인). 달지 않는다.**
      서버가 센서 없이 해결했다: `origin/Server-develop` 의 `520afdf`
      ("feat(alert): 누적 급수량으로 배수트레이 비움을 알린다", 2026-07-31 머지)가
      `device_command.dispensed_ml` **누적량**으로 배수트레이 알림을 판정한다. 커밋 메시지 그대로 —
      *"급수마다 실제 배출량이 device_command.dispensed_ml 에 남으므로 수위 센서를 붙이지 않고
      판정할 수 있다 — 장치 쪽 작업이 없는 것이 이 방식의 핵심 이점이다."*
      임계값은 고정 ml 이 아니라 `recommended_watering_ml × 배수(기본 8)` 이고, 해제는
      `POST /plants/{plantId}/drainage-tray/emptied` 다. 서버측 파일은
      `alert/application/DrainageTrayService.java`·`DrainageTrayEvaluationListener.java`.
      → **GPIO 를 하나 더 쓸 일도, 배선을 늘릴 일도 없다.** 이 항목을 다시 열지 말 것.

      ⚠️ 대신 **우리 쪽 `result/water` 의 `dispensedMl` 이 서버 판정의 유일한 입력**이 됐다.
      이 필드를 빼거나 이름을 바꾸거나 0 으로 채우면 서버의 배수트레이 알림이 조용히 망가진다.
      실제 전송값은 `docs/loops/LOOP_3.md` W5 에서 실측 확인 (`dispensedMl: 50.0`).
- [ ] **급수 안전 게이트 미연동** — `src/actuators/safety.py::WateringGuard` 는 아직 급수대
      수위와 무관하게 동작한다. "물부족 시 급수 자동 중단" 을 추가하려면 이 게이트에
      `water_present`(또는 확정된 `waterLow`) 를 축으로 넣어야 한다.
- [ ] **서버 알림 E2E 미확인** — 서버 코드(`StationWaterLowPushListener` 등)가 존재하는 것과
      `WaterLowMessage` 를 받는 것까지만 확인했다. 실제 앱 푸시/알림 화면까지 뜨는지는 미확인.
- [ ] **`hold_sec=10` 적정성** — 장기 실사용 관찰 필요. 너무 길면 알림 지연, 너무 짧으면 잔떨림
      재발 — 튜닝은 `config/raspberry_pi.yaml` `sensors.water_level.hold_sec` 값만 바꾸면 됨.
- [ ] (기존 미해결, 이 티켓과 무관) 12V 펌프 유량 보정 — `docs/potner-mqtt-pi-handoff.md` 의
      "남은 일" 참고. 같이 묶여 있던 **서버 규약 확인 2건은 2026-08-04 에 조사 완료**
      (촬영은 문제없음 / 급수 `SKIPPED` 는 서버가 거부하는 **불일치** 확인) — 같은 문서의 3번 항목 참고.

---

## 재현 명령

```bash
# 로컬(PC) 테스트
pytest tests/test_water_level.py tests/test_water_level_telemetry.py -v
python -m cli.water_level read --mock

# Pi 배포 (working-tree 변경 전달; 커밋 안 된 실험 중일 때)
scp cli/water_level.py raspberrypi:~/S15P11E104/cli/water_level.py
scp src/mqtt/publisher.py raspberrypi:~/S15P11E104/src/mqtt/publisher.py
scp config/raspberry_pi.yaml raspberrypi:~/S15P11E104/config/raspberry_pi.yaml

# Pi 실물 확인 (서비스 중지 필요 — GPIO27 충돌)
ssh raspberrypi "sudo systemctl stop sensor-collector; cd ~/S15P11E104 && \
  timeout --signal=INT 30 .venv/bin/python -m cli.water_level watch"
ssh raspberrypi "sudo systemctl start sensor-collector"

# 운영 로그로 10초 게이트 확인 (raw 값 + mqtt 발행 개수)
ssh raspberrypi "journalctl -u sensor-collector -f | grep water_present"
```
