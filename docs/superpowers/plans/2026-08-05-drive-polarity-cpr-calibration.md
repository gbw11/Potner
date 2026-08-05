# Drive Polarity and Encoder CPR Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make positive ROS yaw commands rotate the differential-drive robot counter-clockwise and make FIT0403 full-quadrature feedback use 5760 counts per output-shaft revolution.

**Architecture:** Keep ROS wheel targets and encoder feedback in ROS sign convention. Apply explicit `-1.0f` output-polarity constants only at the ESP32 PWM boundary, and use 5760 CPR consistently in firmware and Jetson odometry configuration.

**Tech Stack:** ESP32 Arduino C++, ESP32Encoder, ROS 2 Humble, Python 3, pytest, YAML.

## Global Constraints

- Do not create a Git commit.
- Preserve all unrelated user changes in the dirty worktree.
- Do not change safety, Nav2, exploration, wheel geometry, or speed limits.
- Both ESP32 firmware and Jetson host configuration must use exactly 5760 CPR.
- Encoder signs remain unchanged; only motor-driver output polarity is inverted.

---

## File Map

- `tests/test_firmware_drive_contract.py`: source-level regression contract for firmware CPR and PWM polarity calibration.
- `tests/test_config_consistency.py`: deployed/default host CPR regression assertion.
- `src/potner_firmware/src/main.cpp`: ESP32 PID conversion and final PWM polarity boundary.
- `src/potner_firmware/README.md`: hardware calibration and deployment documentation.
- `src/potner_base/potner_base/base_driver_node.py`: standalone ROS node CPR default.
- `src/potner_base/potner_base/kinematics.py`: ROS-independent drive configuration default.
- `src/potner_bringup/config/potner_params.yaml`: deployed Jetson CPR parameter.

### Task 1: Add failing drive-calibration contracts

**Files:**
- Create: `tests/test_firmware_drive_contract.py`
- Modify: `tests/test_config_consistency.py`

**Interfaces:**
- Consumes: firmware source constants and the host `DriveConfig` default.
- Produces: regression tests requiring `COUNTS_PER_REV = 5760.0f`, left/right output polarity `-1.0f`, polarity application at both PID-to-PWM calls, and host CPR 5760.

- [ ] **Step 1: Write the failing firmware contract tests**

```python
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FIRMWARE = REPO / "src" / "potner_firmware" / "src" / "main.cpp"


def firmware_source():
    return FIRMWARE.read_text(encoding="utf-8")


def test_full_quadrature_uses_5760_counts_per_output_revolution():
    source = firmware_source()
    assert re.search(r"COUNTS_PER_REV\s*=\s*5760\.0f", source)


def test_both_motor_outputs_apply_negative_wiring_polarity():
    source = firmware_source()
    assert re.search(r"LEFT_MOTOR_OUTPUT_SIGN\s*=\s*-1\.0f", source)
    assert re.search(r"RIGHT_MOTOR_OUTPUT_SIGN\s*=\s*-1\.0f", source)
    assert "LEFT_MOTOR_OUTPUT_SIGN * updatePid(wheelL" in source
    assert "RIGHT_MOTOR_OUTPUT_SIGN * updatePid(wheelR" in source
```

- [ ] **Step 2: Change the fixed host CPR assertion to the full-quadrature value**

```python
def test_encoder_resolution_matches_full_quadrature_counting():
    assert DriveConfig().counts_per_rev == 5760
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
python -m pytest tests/test_firmware_drive_contract.py tests/test_config_consistency.py -q
```

Expected: failures show firmware/default/config values are still 1440 and output-polarity constants are absent.

### Task 2: Correct ESP32 closed-loop polarity and count scale

**Files:**
- Modify: `src/potner_firmware/src/main.cpp`
- Modify: `src/potner_firmware/README.md`
- Test: `tests/test_firmware_drive_contract.py`

**Interfaces:**
- Consumes: signed wheel targets from serial `V,<left>,<right>` frames and signed quadrature counts.
- Produces: stable PID duty where positive target produces positive measured wheel speed despite the installed BTS7960 wiring.

- [ ] **Step 1: Set the firmware full-quadrature scale and explicit wiring polarity**

Add beside the hardware constants:

```cpp
constexpr float COUNTS_PER_REV = 5760.0f;
constexpr float LEFT_MOTOR_OUTPUT_SIGN = -1.0f;
constexpr float RIGHT_MOTOR_OUTPUT_SIGN = -1.0f;
```

- [ ] **Step 2: Apply polarity only at the final PWM boundary**

Replace the two control-loop calls with:

```cpp
applyPwm(
    CH_L_R, CH_L_L,
    LEFT_MOTOR_OUTPUT_SIGN * updatePid(wheelL, encLeft.getCount(), dt));
applyPwm(
    CH_R_R, CH_R_L,
    RIGHT_MOTOR_OUTPUT_SIGN * updatePid(wheelR, encRight.getCount(), dt));
```

Do not negate encoder counts, targets, or serial feedback.

- [ ] **Step 3: Document the calibrated count convention and polarity**

Document that FIT0403 provides 1440 pulses per output revolution while `attachFullQuad()` counts four edges per pulse cycle, yielding 5760 software counts, and that both installed motor-driver channels require output sign `-1.0f`.

- [ ] **Step 4: Run the firmware contract tests and verify GREEN**

Run:

```bash
python -m pytest tests/test_firmware_drive_contract.py -q
```

Expected: all firmware contract tests pass.

### Task 3: Align Jetson odometry CPR

**Files:**
- Modify: `src/potner_base/potner_base/base_driver_node.py`
- Modify: `src/potner_base/potner_base/kinematics.py`
- Modify: `src/potner_bringup/config/potner_params.yaml`
- Modify: `tests/test_config_consistency.py`

**Interfaces:**
- Consumes: cumulative full-quadrature tick counts from ESP32 serial feedback.
- Produces: odometry using `meters_per_count = pi * wheel_diameter / 5760`.

- [ ] **Step 1: Change every deployed/default host CPR value to 5760**

Set these exact values:

```python
self.declare_parameter("counts_per_rev", 5760)
```

```python
counts_per_rev: int = 5760
```

```yaml
counts_per_rev: 5760
```

- [ ] **Step 2: Run host configuration and kinematics tests**

Run:

```bash
python -m pytest tests/test_config_consistency.py tests/test_kinematics.py -q
```

Expected: all tests pass and deployed/default values agree.

### Task 4: Regression and deployment verification

**Files:**
- Verify only; do not create or modify additional production files.

**Interfaces:**
- Consumes: completed firmware and host calibration.
- Produces: verified source package ready to copy to Jetson and flash to ESP32.

- [ ] **Step 1: Search for stale production CPR claims**

Run:

```bash
rg -n "COUNTS_PER_REV|counts_per_rev|1440|5760" src tests README.md docs
```

Expected: deployed/default/full-quadrature values are 5760; 1440 remains only where explicitly described as raw pulse specification or in test-local arbitrary fixtures.

- [ ] **Step 2: Run the complete Python test suite**

Run:

```bash
python -m pytest -q
```

Expected: all tests pass with no new failures.

- [ ] **Step 3: Compile firmware when PlatformIO is available**

Run:

```bash
pio run --project-dir src/potner_firmware
```

Expected: firmware build succeeds. If `pio` is unavailable locally, report that limitation and require this command before flashing on the development machine.

- [ ] **Step 4: Verify the diff is scoped and uncommitted**

Run:

```bash
git diff -- src/potner_firmware/src/main.cpp src/potner_firmware/README.md src/potner_base/potner_base/base_driver_node.py src/potner_base/potner_base/kinematics.py src/potner_bringup/config/potner_params.yaml tests/test_config_consistency.py tests/test_firmware_drive_contract.py
git status --short
```

Expected: only the planned calibration changes appear in the scoped diff, existing unrelated changes remain untouched, and no commit is created.

- [ ] **Step 5: Perform hardware validation after deployment**

With both drive wheels off the ground, command `angular.z = +0.25 rad/s` for at most 15 seconds. Verify left wheel backward, right wheel forward, positive `/odom angular.z` near the commanded order of magnitude, and `/odom linear.x` near zero. Stop immediately if either wheel accelerates instead of settling.
