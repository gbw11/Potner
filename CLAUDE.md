# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
It is a map, not a manual — details live in the linked docs; don't duplicate them here.

## What this is

A monorepo where **each part has its own branch with a different codebase**: `Robot-master` (ROS2 Jetson
robot), `App-master` (Flutter), `Server-master` (Spring Boot), `Raspberry-master` (this branch's family —
device station). Checking out a different `*-master`/`*-feature` branch can mean an entirely different
project structure — re-verify paths in this file against `git branch --show-current` before trusting them.

This branch is a plain Python project (no ROS/colcon) running on a Raspberry Pi (or Jetson Nano, or a
Windows/mock dev machine). It collects sensor data, controls actuators (water pump, fan, camera), talks to
a Spring Boot backend over MQTT, and drives a local LLM chat/briefing loop. `main.py` is the collector
entry point; `cli/plant.py` is the interactive entry point (brief/report/chat/tick).

`.claude/` (agents, skills, settings) is `.gitignore`d — nothing under it is ever committed, so a file left
there from work on a different branch survives `git checkout` as an untracked leftover. If something under
`.claude/` looks inconsistent with the checked-out branch, check `git status`/`.gitignore` before trusting it.
Likewise `src/potner_*/` and `voice-chat-server/` are untracked Robot-branch leftovers on this branch — not
part of this codebase, ignore them.

## Map

| Path | What's there |
|---|---|
| `main.py` | Collector entry point (`--once` / loop, `--config path.yaml`) |
| `cli/plant.py` | Interactive entry point: `brief` / `report` / `chat` / `tick` / `llm-status` |
| `cli/water.py`, `cli/fan.py`, `cli/capture_photo.py` | Actuator/camera CLI (`dispense`/`calibrate`/`prime`/`run`, etc.) |
| `src/sensors/` | BH1750 (light), DHT22/AHT20 (climate), ADS1115 (soil) drivers + `factory.py` + mock |
| `src/actuators/` | Water pump (L298N/relay), fan (MOSFET) — see quick map below |
| `src/vision/` | Camera capture (OV5647/Picamera2), storage/index, snapshot naming — see quick map below |
| `src/mqtt/` | Broker client, telemetry/heartbeat publisher, water & capture command listeners |
| `src/events/` | JSONL event log (state changes, used for report/diary context) |
| `src/status/` | Threshold-based normal/abnormal rules (`rules.py`) + labels (`models.py`) — judgment is always local, never LLM |
| `src/dialogue/`, `src/llm/` | brief/report/chat orchestration + OpenAI-compatible (GMS) client, tool use, template fallback |
| `src/voice/` | STT/TTS adapter (console today; Whisper/TTS API later) |
| `src/integrations/`, `src/transport/` | Spring Boot HTTP push (soil moisture) |
| `config/default.yaml`, `config/raspberry_pi.yaml` | `platform: mock\|raspberry_pi\|jetson` switches drivers; single source of truth for pump/fan/camera/mqtt/status_rules tuning |
| `tests/` | Unit tests, all pass on Windows without hardware (`conftest.py` sets up mocks) |
| `docs/potner-mqtt-pi-handoff.md` | Current Pi↔broker handoff notes — device IDs, topics, known ACL blocker, resume checklist |
| `scripts/`, `systemd/` | Pi deployment/SSH helpers, systemd unit for the collector service |
| `README.md` | Full hardware wiring, calibration, and PC↔Pi deploy walkthrough |

## Commands

```bash
pytest tests/ -v                     # full suite, no hardware required (mock platform)
pytest tests/test_pump.py -v         # single file
python main.py --once                # one collection cycle (mock on Windows, real sensors on Pi)
python cli/plant.py brief            # LLM/template status briefing
python cli/water.py dispense --ml 50 # trigger a timed pump run
```

No `pytest.ini` marker magic beyond `testpaths = tests; pythonpath = .` — tests import `src.*` directly,
no build step needed.

## Invariants

These hold in the current code; if you're about to change one, that's a deliberate decision, not an accident.

- Status judgment (normal/abnormal) is always computed locally in `src/status/rules.py`; the LLM only
  produces wording/conversation, never the judgment itself (`README.md` 수정 포인트).
- Watering duration is time-controlled, not volume-sensed: `duration = ml/flow_ml_per_sec + startup_sec`,
  clamped to `[min_run_sec, max_run_sec]` (`src/actuators/base.py::WaterPump.plan_dispense`). Calibrate via
  `cli/water.py calibrate [--apply]`, not by hand-editing the formula.
- Soil moisture never produces a volume — a `%` reading cannot yield `ml`. It is a pre-dispense safety gate
  only (`src/actuators/safety.py::WateringGuard`), and a missing or failing soil sensor fails open so
  watering still works. The other three gates (per-dose cap, interval, 24h budget) need no sensor at all.
- Captured images pass a brightness gate before they go anywhere else: `src/vision/quality.py::check_image`
  runs in `src/mqtt/capture_command.py` after capture, and a rejected image is never uploaded
  (`camera.quality.on_failure: reject`). Setting it to `warn` lets rejects through with a logged warning.
- `config/*.yaml` is the single source of truth for driver selection, pins, and tuning constants
  (`platform: mock|raspberry_pi|jetson` switches every sensor/actuator driver). Don't hardcode pin numbers
  or thresholds in code that already reads them from config.
- On the Pi, an inherited `DEVICE_ID` env var silently overrides the yaml `mqtt.device_id` with the board
  serial, which the server then rejects as unregistered — `unset DEVICE_ID` before running
  (`docs/potner-mqtt-pi-handoff.md`).
- Mosquitto ACL denials are silent: a denied `read` still returns SUBACK "Granted" with no message ever
  delivered. Don't assume a command listener is broken from code alone — verify with a round-trip publish
  test first (`docs/potner-mqtt-pi-handoff.md`, `pump-watering-status`/`capture-command-status` history).
- Tests never hit real hardware or the real MQTT broker/GMS endpoint — `platform: mock` and monkeypatched
  network calls only. A test that needs real hardware/network to pass is a bug in the test.

## Prohibited

- Committing secrets — `MQTT_PASSWORD`, `OPENAI_API_KEY`/`OPENAI_BASE_URL`/`OPENAI_MODEL` (GMS, not real
  OpenAI) live in `.env` only.
- Treating `sensor-collector/` (if present) as live code — it's pre-restructure leftover, ignored.
- Editing `src/potner_*/` or `voice-chat-server/` here — those are untracked Robot-branch leftovers, not part
  of this branch (see "What this is" above); don't let them shadow this branch's actual modules.

## Logging

Standard `logging` only — `logging.getLogger(__name__)` in every module, never `print()` for anything
that is a log. Handler setup happens exactly once at the entry point via
`src/logging_setup.py::setup_logging(config)`; the `logging:` config section controls level, console
output, and the rotating file (`data/app.log`). Don't add handlers anywhere else.

`print()` still belongs in `cli/*` for command output the user reads on stdout — that's UI, not logging.

Structured history that needs to be queried back (state changes, capture history) goes to
`src/events/store.py::EventStore` (JSONL) — a separate channel from the human-readable app log, not a
replacement for it.

## Parallel work: stream boundaries

Four work streams run in parallel via subagents (`.claude/agents/*.md`), each in an isolated git
worktree. Ownership map — an agent may freely edit only its own row:

| Stream | Agent | Tickets | Owns | Tests go in |
|---|---|---|---|---|
| A 급수 제어 | `watering` | 급수량 계산, 과급수 방지 | `src/actuators/*`, `src/mqtt/water_command.py`, `cli/water.py`, new `src/actuators/safety.py`, config `pump:` | `tests/test_watering_volume.py`, `tests/test_water_safety.py` |
| B 촬영 파이프라인 | `capture-pipeline` | 촬영 로그, 파일명 규칙 | `src/vision/store.py`, `src/vision/snapshot.py`, new `src/vision/naming.py`, `src/mqtt/capture_command.py` | `tests/test_capture_pipeline.py` |
| C 이미지 품질 | `brightness` | 밝기 검사 | new `src/vision/quality.py`, config `camera.quality:` | `tests/test_brightness_check.py` |
| D 업로드·메타데이터 | `uploader` | 이미지 업로드, 촬영 메타데이터 | new `src/transport/uploader.py`, `src/integrations/spring.py`, config `upload:` | `tests/test_image_upload.py` |

Shared files are **frozen during parallel runs**: `src/vision/base.py` (`CaptureResult` schema),
`src/vision/camera.py`, `src/collector.py`, `main.py`, `src/logging_setup.py`, `src/mqtt/client.py`,
`src/mqtt/publisher.py`, `src/sensors/*`, `src/events/store.py`, every other section of
`config/default.yaml`/`config/raspberry_pi.yaml`, and all pre-existing tests. A stream that needs a
shared-file change reports it as a proposed diff; the coordinating session applies those sequentially
after merging, then runs `pytest tests/ -v` as the integration gate. Prefer new modules over touching
shared files at all.

**Not parallelized**: 촬영 재시도 (retry) composes B's capture pipeline with C's brightness check as a
retry trigger, so it runs sequentially after A–D merge, not alongside them.

## Actuator/vision quick map

`src/actuators/`:

| File | Role |
|---|---|
| `base.py` | `WaterPump`/`Fan` base classes — duration↔volume math, speed control, capping |
| `pump.py` | `L298NWaterPump`, `RelayWaterPump` — real GPIO drivers, `_run(seconds)` only |
| `fan.py` | MOSFET-driven PWM fan |
| `mock.py` | `MockWaterPump`/`MockFan` — log-only, used when `platform: mock` |
| `factory.py` | `build_pump()`/`build_fan()` — reads `config/*.yaml` into driver instances |

`src/vision/`:

| File | Role |
|---|---|
| `base.py` | `CaptureResult` dataclass — shared schema, treat as frozen during parallel work |
| `camera.py` | Picamera2 driver, lazy-init (opens on first capture, not at collector startup) |
| `mock.py` | Dummy PNG capture for PC/mock |
| `naming.py` | Filename rule, single source: `frame_YYYYMMDD_HHMMSS[_n].ext` |
| `store.py` | `CameraStore` — capture + `index.jsonl` image index + optional `EventStore` capture history |
| `snapshot.py` | `next_capture_path()` — picamera2 entry point, delegates to `naming.py` |
| `quality.py` | Brightness check (ok/too_dark/too_bright) — stdlib PNG decoder, optional Pillow for JPEG, `camera.quality:` config |
