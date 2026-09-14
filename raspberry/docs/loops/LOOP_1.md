# 루프 1 — 전체 시뮬레이션 (예측 → 시행 → 수정)

날짜: 2026-08-04 / 브랜치: `Raspberry-fix/full-simulation-debug` (Raspberry-develop 하위)

## 1. 시행 전 예측

사용자 시나리오: 수집 서비스 기동 → 센서 수집/보고 → 상태 브리핑 →
자유 대화 → 급수/팬/촬영 명령 → 수위 확인 → 종료.

| # | 시행 | 예측 결과 |
|---|---|---|
| P1 | `pytest tests/ -q` | 전건 통과 (문서 기준 최근 전건 통과 상태. 하드웨어/네트워크 불필요) |
| P2 | `python main.py --once` | mock 플랫폼 1회 수집, `spring=skip mqtt=skip`, T/H 값 출력 후 종료코드 0 |
| P3 | `python cli/plant.py llm-status` | `.env`에 `GMS_API_KEY` 있음 → `LLM=on ... model=gpt-4.1-nano` (단, .env 자동 로드 여부에 따라 off일 수 있음 — off면 로드 경로 버그로 간주) |
| P4 | `python cli/plant.py tick` | mock 수집 1회 + 상태 라벨 dict 출력, 이벤트는 없거나 상태변화 1건 |
| P5 | `python cli/plant.py brief` | LLM on이면 실 GMS 1회 호출로 2~4문장 브리핑, off/실패면 템플릿 문장 |
| P6 | `python cli/water.py dispense --ml 50` | mock 펌프 2.0초 가동 로그(50ml / 25ml/s), 안전 게이트 통과(직전 기록이 하루 이상 지남) 후 water_history.json 갱신 |
| P7 | `python cli/water.py dispense --ml 50` 직후 재실행 | `min_interval_sec: 300` 게이트에 걸려 SKIP 회신 |
| P8 | `python cli/fan.py run --seconds 2` | mock 팬 100% 2초 가동 로그, 종료코드 0 |
| P9 | `python cli/plant.py capture` | mock 더미 PNG 저장 + index.jsonl 추가 + 밝기검사 ok, `"ok": true` |
| P10 | `python cli/water_level.py read` | mock 드라이버 water_present 값 출력 (플랫폼 mock이라 실핀 안 잡음) |
| P11 | flake8 CI 게이트 (`E9,F63,F7,F82`) | 0건 |

불변식 준수: 테스트는 실 GMS/브로커/하드웨어에 절대 안 나감.
P5의 실 GMS 1회 호출은 테스트가 아니라 수동 스모크로 허용 범위.

## 2. 시행 결과 (예측 대조)

| # | 실제 결과 | 예측 대조 |
|---|---|---|
| P1 | `pytest tests/ -q` exit 0, 실패 0건, skip 2건 | 일치 |
| P2 | `python main.py --once` exit 0, `spring=skip mqtt=skip`, T=22.02C H=55.66%. `water_present=None`은 `config/default.yaml`의 `sensors.water_level.enabled: false`(mock PC 기본 꺼짐, 주석에 명시)에 따른 정상 동작 | 일치 (water_present는 예측에 없던 세부사항, 정상) |
| P3 | `LLM=on provider=gms model=gpt-4.1-nano` | 일치 |
| P4 | mock 수집 1회 + 상태 라벨 dict + `session_start` 이벤트 1건. **콘솔 한글이 깨져 나옴** — Windows 기본 콘솔 인코딩(cp949) 때문이며 `PYTHONIOENCODING=utf-8`로 재실행하면 정상 출력됨. 코드 버그 아님(대상 배포 환경은 Pi/Linux) | 대체로 일치, 콘솔 인코딩은 예측에 없던 관찰사항 |
| P5 | 실 GMS 1회 호출로 2문장 브리핑 생성, exit 0 | 일치 |
| P6 | mock 펌프 2.00s 가동, "약 50 ml / 2.0s" | 일치 |
| P7 | 즉시 재실행 시 `TOO_SOON` 사유로 차단, exit 3, "정말 급수하려면 --force" 안내 | 취지 일치 (예측은 "SKIP"이라 표현했으나 실제 사유 코드는 TOO_SOON — 문서상 표현 차이일 뿐 동작은 예측대로 최소 간격 게이트가 막음) |
| P8 | 예측 문서의 `--seconds` 플래그는 실제 존재하지 않음(`fan.py run: error: the following arguments are required: --sec`). `--sec 2`로 재실행하니 100% 2.0s 가동 후 정지, exit 0 | **불일치**: 예측 문서의 플래그명이 실제 CLI(`--sec`)와 다름 — 코드가 아니라 이 문서(1절 예측)의 오기. 코드 수정 불필요 |
| P9 | mock PNG 저장(`data/camera/frame_20260804_083800.png`), `"ok": true` | 일치 |
| P10 | `--mock` 사용 시 정상 JSON 출력(`water_present: false`). `--mock` 없이 실행하면 지난 세션에 추가한 `ImportError` 처리대로 traceback 없이 "오류: 수위 센서 읽기에는 gpiozero 가 필요합니다 ... --mock 을 붙이세요" + exit code 2 | 일치 (P10 관련 수정이 의도대로 동작함을 재확인) |
| P11 | `flake8 --select=E9,F63,F7,F82 .` exit 0, 0건 | 일치 |

**요약**: 전 항목 예측대로 동작 확인. 코드 버그는 이번 시행에서 새로 발견되지 않음 — P10의 기존
수정(아래 3절)이 정확히 의도대로 동작함을 재확인한 것이 이번 시행의 핵심 성과. P8은 이 문서 1절의
예측 플래그명이 틀렸던 것뿐이라 코드 변경 대상 아님. P4의 콘솔 한글 깨짐은 Windows 콘솔 코드페이지
문제로 Pi 배포와 무관해 코드 수정 대상에서 제외.

## 3. 수정/삭제/추가

- `cli/water_level.py`: `main()`에서 `cmd_read`/`cmd_watch` 호출을 `try/except ImportError`로 감싸,
  PC에서 `--mock` 없이 실행해 `gpiozero` import가 실패할 때 traceback 대신
  "오류: ... --mock 을 붙이세요" 안내 + exit code 2를 반환하도록 수정 (P10 시행 중 발견).
- `tests/test_water_level.py`: 위 동작을 검증하는
  `test_cli_read_without_gpiozero_exits_cleanly_with_mock_hint` 테스트 추가.
- 삭제된 것 없음. 이번 루프 재시행에서는 위 두 파일 외 추가 수정 없음(2절 요약 참고 — 신규 버그 미발견).
