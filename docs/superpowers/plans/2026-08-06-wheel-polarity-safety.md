# Wheel Polarity and Stop Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the installed drivetrain's encoder and motor polarities and guarantee zero PWM after stop or watchdog timeout.

**Architecture:** Normalize raw encoder counts once inside the ESP32 firmware, then use those normalized counts for PID and Jetson feedback. Guard the PID output with an explicit drive-enable state that only a valid velocity command may enable.

**Tech Stack:** Arduino C++, ESP32Encoder, PlatformIO, Python source-contract tests

## Global Constraints

- Do not commit changes.
- Preserve `COUNTS_PER_REV = 5760.0f`.
- Left encoder sign is integer `-1`; right encoder sign is integer `+1`.
- Left motor output sign is `+1.0f`; right motor output sign is `-1.0f`.

---

### Task 1: Firmware contract tests

**Files:**
- Modify: `tests/test_firmware_drive_contract.py`
- Test: `tests/test_firmware_drive_contract.py`

**Interfaces:**
- Consumes: firmware source at `src/potner_firmware/src/main.cpp`
- Produces: executable contracts for polarity normalization and stop gating

- [ ] Replace the obsolete both-negative motor polarity assertion with assertions for the measured encoder and motor signs.
- [ ] Add assertions that normalized counts are used by PID and serial feedback.
- [ ] Add assertions that stop and watchdog state disable PID PWM.
- [ ] Add assertions that malformed or non-finite velocity fields cannot reach drive enable.
- [ ] Run the tests before production changes and confirm the new contracts fail.

### Task 2: Firmware implementation

**Files:**
- Modify: `src/potner_firmware/src/main.cpp`
- Test: `tests/test_firmware_drive_contract.py`

**Interfaces:**
- Consumes: raw `ESP32Encoder::getCount()` values and valid `V`/`S` serial frames
- Produces: normalized cumulative counts, normalized PID measurement, and gated PWM output

- [ ] Define the four measured polarity constants and a normalized-count helper.
- [ ] Add a drive-enabled state that valid non-zero velocity frames set and stop, zero-velocity, and watchdog paths clear.
- [ ] Validate exact field count and fully parse two finite velocity values before changing drive state.
- [ ] While disabled, force both PWM outputs to zero and synchronize PID count baselines.
- [ ] Use normalized counts for PID and `O,...` serial feedback.
- [ ] Run the firmware contract tests and confirm they pass.

### Task 3: Documentation and verification

**Files:**
- Modify: `src/potner_firmware/README.md`
- Test: `tests/test_firmware_drive_contract.py`

**Interfaces:**
- Consumes: implemented firmware constants and stop behavior
- Produces: Jetson upload and lifted-wheel verification instructions

- [ ] Document the measured encoder and motor polarity values.
- [ ] Document that stop/watchdog disables PID PWM until the next valid velocity frame.
- [ ] Run all relevant Python tests with the available runtime or a standard-library fallback harness.
- [ ] Run `pio run` if PlatformIO is available locally; otherwise provide the exact Jetson build command and state that hardware compilation remains pending.
- [ ] Review the final diff and provide safe low-speed Jetson verification commands.

### Task 4: Minimum drive feed-forward

**Files:**
- Modify: `tests/test_firmware_drive_contract.py`
- Modify: `src/potner_firmware/src/main.cpp`
- Modify: `src/potner_firmware/README.md`

**Interfaces:**
- Consumes: normalized wheel target and existing PID feedback output
- Produces: signed 0.16 initial duty compensation for non-zero targets only

- [ ] Add a failing contract test for positive, negative, and zero target feed-forward branches.
- [ ] Define `MIN_DRIVE_DUTY = 0.16f` from the measured start threshold.
- [ ] Add signed target feed-forward before final PWM output clamping.
- [ ] Make a zero wheel target clear residual PID state and return zero output.
- [ ] Reset per-wheel PID history before output when the target direction reverses.
- [ ] Preserve the whole-robot stop gate and zero-target behavior.
- [ ] Run contracts, diff checks, and PlatformIO where available.
