# 루프 2 — 루프 1이 건너뛴 경로 (예측 → 시행 → 수정)

날짜: 2026-08-04 / 브랜치: `Raspberry-fix/full-simulation-debug` (Raspberry-develop 하위)

## 0. 이 루프를 도는 이유

루프 1은 예측 11건이 전부 맞아 **새로 배운 게 거의 없었다** — 시나리오가 happy path 였다는 뜻이다.
다시 읽어보니 루프 1의 1절 시나리오("… → 자유 대화 → 급수/팬/촬영 명령 → 수위 확인 → 종료")를
P1~P11 표로 옮기는 과정에서 실제로 빠진 구간이 있었다:

- **`chat`(자유 대화) 누락** — 시나리오에 명시돼 있는데 표에 없다. `report`/`push-soil`/`camera-list` 도 미시행.
- **수위 센서가 꺼진 채로 돌았다** — `sensors.water_level.enabled: false`(mock 기본)라 전 구간
  `water_present=None`. 직전 티켓의 핵심인 **10초 hold 게이트가 시뮬레이션에서 한 번도 안 돌았다.**
- **연속 루프 미검증** — `--once` 만 봤고 `interval_sec: 5` 기동/종료(시나리오의 "기동"·"종료")는 안 봤다.
- **실패 경로 전무** — 브로커 끊김, LLM 끊김 같은 실제 Pi 부팅 상황이 전혀 안 들어갔다.

루프 2는 이 네 구멍을 목표로 한다. 루프 1의 P8 교훈(예측 문서에 플래그명을 잘못 적으면 코드 버그가
아니라 문서 노이즈만 생긴다)을 반영해, **인자 표면은 `--help` 로 미리 확인하고 동작만 예측**한다.

불변식 준수: 실 MQTT 브로커(`i15e104.p.ssafy.io`)에는 붙지 않는다 — 브로커 실패 경로는 아무도 안 듣는
**로컬 死주소**(`127.0.0.1`)로 재현한다. Windows 콘솔 cp949 깨짐은 루프 1에서 환경 문제로 결론났으므로
전 항목 `PYTHONIOENCODING=utf-8` 로 실행한다.

## 1. 시행 전 예측

### A. 시나리오 누락분 (대화/조회 계열)

| # | 시행 | 예측 결과 |
|---|---|---|
| Q1 | `plant.py chat` (인자 없는 대화형, stdin EOF) | **불확실 — 반반으로 본다.** 깔끔히 종료(exit 0)면 정상이고, `EOFError` traceback 이 뜨면 버그. 대화형 CLI 가 비대화형 stdin 을 안 막아둔 경우가 흔하다 |
| Q2 | `plant.py report --limit 5` | `data/events.jsonl` 에 루프 1 tick 들이 남긴 `session_start` 이벤트 기반 리포트 문장, exit 0 |
| Q3 | `plant.py camera-list --limit 5` | 루프 1 P9 가 저장한 `frame_20260804_083800.png` 포함 목록 출력, exit 0 |
| Q4 | `plant.py push-soil` | `backend.enabled: false` + soil 센서 `enabled: false` → 전송 안 하고 "비활성/값 없음" 안내 후 종료. 크래시하면 버그 |

### B. 수위 센서를 켠 수집 (이번 루프의 핵심)

`sensors.water_level.enabled: true` 로 바꾼 시뮬레이션용 config 를 `--config` 로 넘긴다
(레포의 `config/default.yaml` 은 건드리지 않는다 — mock 기본 꺼짐은 의도된 설정).

| # | 시행 | 예측 결과 |
|---|---|---|
| Q5 | 수위 켠 config 로 `main.py --once` | `water_present=False`(=물 없음). 근거: `factory.py:35` 가 `MockFloatSwitchSensor(invert=invert)` 로만 만들어 `values=[True]` 고정 → `water_present = closed != invert = True != True = False` |
| Q6 | 같은 config 로 연속 루프 ~20초 | 5초 간격 4회 내외 수집, `water_present` 가 **계속 False 고정**. 즉 PC 에서는 "물 부족" 방향만 재현되고 **복구(false→true) 방향은 재현 불가**. mock 에 `toggle`/`values` 옵션이 있는데 `factory.py` 가 config 로 노출하지 않는 게 원인 — 이번 루프의 유력한 수정 후보 |
| Q7 | 위 실행 후 `data/readings.csv` | `water_present` 컬럼이 존재하고 `False` 로 기록됨 |

### C. 기동 / 종료

| # | 시행 | 예측 결과 |
|---|---|---|
| Q8 | 연속 루프 실행 중 SIGINT(Ctrl+C) | 정리 로그 남기고 조용히 종료, traceback 노출 없음. `KeyboardInterrupt` 스택이 그대로 뜨면 버그 |

### D. 실패 경로

| # | 시행 | 예측 결과 |
|---|---|---|
| Q9 | `mqtt.enabled: true` + `host: 127.0.0.1`(아무도 안 들음) 로 연속 루프 | 연결 실패를 warn 으로 남기고 **수집 루프는 계속 살아있어야** 한다(개별 센서 실패가 루프를 안 죽이는 기존 정책과 같은 결). 기동 시점에 예외로 프로세스가 죽으면 버그 — Pi 가 네트워크보다 먼저 부팅되는 실제 상황이라 중요 |
| Q10 | `OPENAI_API_KEY` 제거 후 `plant.py brief` | LLM off 로 판정하고 템플릿 문장으로 폴백, exit 0 (`README` 의 템플릿 폴백 규약) |
| Q11 | `water.py dispense --ml 50 --force` | 루프 1 P7 에서 안내한 `--force` 가 실제로 인터벌 게이트를 우회해 2.0s 급수, exit 0 |

## 2. 시행 결과 (예측 대조)

| # | 실제 결과 | 예측 대조 |
|---|---|---|
| Q1 | `EOFError: EOF when reading a line` traceback + exit 1 (`src/voice/console.py:20` → `service.py:134`) | **버그 발견.** "반반" 으로 본 쪽 중 나쁜 쪽이 나왔다 |
| Q2 | 이벤트 기반 리포트 문장 정상 출력, exit 0 | 일치 |
| Q3 | 루프 1 P9 프레임(`frame_20260804_083800.png`) 포함 3건 출력, exit 0 | 일치 |
| Q4 | `{"enabled": false, "ok": true, "message": "disabled"}`, exit 0, 크래시 없음 | 일치 |
| Q5 | `water_present=False` | 일치 (mock `values=[True]` + `invert=True` 라는 근거 추론까지 맞음) |
| Q6 | 5초 간격 10주기 연속 **`water_present=False` 고정**. 물부족→복구 흐름 재현 불가 확인 | 일치 — 예측한 제약이 실측으로 확정됐다. 3절에서 해소 |
| Q7 | `readings.csv` 에 `water_present` 컬럼 존재, `False` 기록 | 일치 |
| Q8 | **코드상 정상**: `main.py:65-70` 이 SIGINT/SIGTERM 을 `_stop` 으로 받아 `running=False` 만 세우고, 루프가 `time.sleep(0.2)` 폴링으로 0.2초 내 빠져나와 `finally` 에서 리스너·펌프·수집기를 정리 후 `return 0`. traceback 경로 없음. **단 실행 검증은 실패** — Git Bash 의 MSYS2 `timeout --signal=INT` 가 native Windows `python.exe` 에 SIGINT 를 전달하지 못해 18초 지정이 45초까지 안 죽었다 | 예측 일치(코드 기준). 실행 검증은 **이 환경에서 불가** — Pi(Linux)에서는 `docs/water-level-sensor-handoff.md` 의 `timeout --signal=INT 30` 사용 이력으로 동작 확인됨 |
| Q9 | `mqtt connect failed (will retry): [WinError 10061]` warn → 기동 로그 `mqtt=retrying 127.0.0.1:1884` → 수집 정상 진행, exit 0 | 일치. Pi 가 네트워크보다 먼저 부팅돼도 안전하다는 게 확인됐다 |
| Q10 | **예측 빗나감.** `urllib.error.URLError` traceback + exit 1 (`service.py:74` 의 `self.llm.complete()` 무방비 호출). 템플릿 폴백이 전혀 안 걸렸다 | **버그 발견.** 같은 파일의 `chat_once()`(99-111행)는 이미 `try/except` 폴백을 갖고 있는데 `brief`/`report` 만 무방비였던 **일관성 결여**가 원인 |
| Q11 | `warn: --force: 과급수 가드를 건너뜁니다.` 후 2.0s 급수, exit 0 | 일치 |

**요약**: 예측 11건 중 **9건 적중, 1건 빗나감(Q10), 1건 환경 제약으로 실행 검증 불가(Q8)**.
루프 1이 0건 발견이었던 것과 달리 **실제 버그 2건(Q1·Q10)** 을 잡았다. 둘 다 "네트워크/입력이 끊기는
현실 상황" 경로였고, 루프 1이 happy path 만 훑었기 때문에 지나쳤던 것들이다.

### 부수적으로 알게 된 것

- **LLM 엔드포인트는 env 가 config 를 이긴다** — `src/llm/client.py:175`
  `os.environ.get("OPENAI_BASE_URL") or llm.get("base_url")`. 그래서 `--config` 로 `llm.base_url` 을
  바꿔도 `.env` 에 `OPENAI_BASE_URL` 이 있으면 무시된다(Q10 재현 중 실 GMS 로 나가버려서 알게 됨).
  `.env` 로딩은 `override=False` 라 **프로세스 env > .env > config** 순. 의도된 설계(CLAUDE.md: 엔드포인트·키는
  `.env` 전용)지만, config 의 `llm.base_url` 이 사실상 죽은 값이 될 수 있다는 점은 기억해 둘 것.
- 빈 문자열 env(`OPENAI_API_KEY=""`)는 `or` 폴백에 걸려 무효 — 끊김 재현에는 **죽은 주소**를 써야 한다.

## 3. 수정/삭제/추가

### 수정 1 — LLM 이 끊겨도 브리핑/리포트는 나온다 (Q10)

`src/dialogue/service.py`: `_llm_text()` 헬퍼를 추가해 `brief()`/`report()` 가 이걸 거치게 했다.
LLM 호출이 실패하면 `None` 을 돌려 **기존의 `if not text: render_briefing(...)` 폴백 경로를 그대로 태운다**
(새 폴백을 만든 게 아니라 이미 있던 걸 쓰게 만든 것). 실패는 조용히 삼키지 않고 `log.warning` 으로 남긴다.
`chat_once()` 가 쓰던 폴백 정책과 같은 결로 맞춘 것 — 판단은 로컬 규칙이 하므로 LLM 이 죽어도 잃는 건
말투뿐이다. 모듈 로거(`log = logging.getLogger(__name__)`)도 이 파일에 처음 추가했다(로깅 규약).

### 수정 2 — `chat` 이 EOF/Ctrl+C 로 죽지 않는다 (Q1)

`src/dialogue/service.py::chat_loop()`: `stt.listen()` 을 `try/except (EOFError, KeyboardInterrupt)` 로
감싸 "종료" 를 친 것과 동일하게 정상 종료시킨다. Ctrl+D·Ctrl+C·파이프 입력 끝이 모두 같은 경로.

### 수정 3 — mock 수위 센서로 물부족→복구를 PC 에서 재현 가능하게 (Q6)

`src/sensors/factory.py`: `MockFloatSwitchSensor` 는 원래 `values`/`toggle` 을 지원하는데 factory 가
`invert` 만 넘기고 있어 PC 에서는 "물 없음" 고정이었다. `sensors.water_level.mock_values`/`mock_toggle`
을 config 로 이어줬다. `config/default.yaml` 에 두 키를 주석과 함께 추가.
**`config/raspberry_pi.yaml` 에는 일부러 넣지 않았다** — mock 전용 손잡이라 실기 설정에 노이즈이고,
factory 가 키 부재 시 기존 동작(고정값)을 그대로 쓴다.

검증: `mock_values: [false, false, true]` + `interval_sec: 2` 로 실제 수집 루프를 돌려
`water_present=True, True, False, False, False` 전환을 PC 에서 확인 — Q6 이 불가능하다고 한 흐름이 재현된다.

### 테스트

- 신규 `tests/test_dialogue_fallback.py` (5개) — LLM 끊김 시 brief/report 폴백, `available()==False` 면
  호출조차 안 함, chat_loop 의 EOF/Ctrl+C 정상 종료.
- `tests/test_water_level_telemetry.py` (+3개) — mock 기본값이 "물 없음" 고정임을 **보존**하는 테스트와
  `mock_values` 시퀀스 재생, `mock_toggle` 교대.
- 전체 **348 passed, 2 skipped** (루프 2 이전 342 → 신규 8개). flake8 `E9,F63,F7,F82` 0건.

### 삭제

없음.

## 4. 다음 루프(있다면) 후보

- **MQTT 왕복 전 구간이 아직 시뮬레이션 밖** — Q9 는 "브로커가 죽었을 때"만 봤다. 급수/촬영 명령
  리스너의 수신→실행→회신은 단위 테스트로만 덮여 있고, 로컬 브로커를 띄운 통합 시뮬레이션은 없다.
- **`hold_sec` 게이트의 수집 루프 레벨 실행 검증** — 수정 3 으로 이제 가능해졌지만 이번 루프에서는
  전환 재현까지만 하고 10초 게이트가 실제 발행을 거르는 것까지는 안 봤다(단위 테스트로는 덮여 있음).
- **Windows 콘솔 cp949** — 루프 1·2 모두 `PYTHONIOENCODING=utf-8` 로 우회했다. Pi 배포엔 무관하지만
  PC 개발자가 매번 밟는 턱이라, 엔트리포인트에서 `sys.stdout.reconfigure(encoding="utf-8")` 를
  할지는 결정이 필요하다(하면 편하지만 엔트리포인트 부작용이 는다).
