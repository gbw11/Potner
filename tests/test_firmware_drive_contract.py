"""ESP32 drive calibration values must match the installed hardware."""

import re
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
FIRMWARE = REPO / "src" / "potner_firmware" / "src" / "main.cpp"


def firmware_source():
    return FIRMWARE.read_text(encoding="utf-8")


def function_source(name):
    """Return one C++ function body using balanced braces."""
    source = firmware_source()
    match = re.search(
        rf"\b(?:void|bool|float|int64_t)\s+{name}\s*\([^)]*\)\s*\{{", source
    )
    assert match, f"missing firmware function: {name}"

    depth = 0
    for index in range(match.end() - 1, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[match.start():index + 1]

    raise AssertionError(f"unterminated firmware function: {name}")


def test_full_quadrature_uses_5760_counts_per_output_revolution():
    source = firmware_source()

    assert re.search(r"COUNTS_PER_REV\s*=\s*5760\.0f", source)


def test_encoder_polarities_match_forward_hand_rotation_measurement():
    source = firmware_source()

    assert re.search(r"LEFT_ENCODER_SIGN\s*=\s*-1", source)
    assert re.search(r"RIGHT_ENCODER_SIGN\s*=\s*\+?1", source)


def test_motor_output_polarities_match_installed_bts7960_wiring():
    source = firmware_source()

    assert re.search(r"LEFT_MOTOR_OUTPUT_SIGN\s*=\s*\+?1\.0f", source)
    assert re.search(r"RIGHT_MOTOR_OUTPUT_SIGN\s*=\s*-1\.0f", source)
    assert "LEFT_MOTOR_OUTPUT_SIGN * updatePid(" in source
    assert "RIGHT_MOTOR_OUTPUT_SIGN * updatePid(" in source


def test_normalized_encoder_counts_feed_pid_and_odometry_feedback():
    source = firmware_source()

    assert "normalizedLeftCount()" in source
    assert "normalizedRightCount()" in source
    assert re.search(
        r"updatePid\(\s*wheelL,\s*normalizedLeftCount\(\),\s*dt\s*\)", source
    )
    assert re.search(
        r"updatePid\(\s*wheelR,\s*normalizedRightCount\(\),\s*dt\s*\)", source
    )
    assert 'String((long)normalizedLeftCount())' in source
    assert 'String((long)normalizedRightCount())' in source


def test_nonzero_targets_receive_signed_minimum_drive_feedforward():
    source = firmware_source()
    feedforward = function_source("minimumDriveFeedforward")
    update_pid = function_source("updatePid")

    assert re.search(r"MIN_DRIVE_DUTY\s*=\s*0\.16f", source)
    assert re.search(
        r"if\s*\(targetMps\s*>\s*0\.0001f\)\s*return\s+MIN_DRIVE_DUTY;",
        feedforward,
    )
    assert re.search(
        r"if\s*\(targetMps\s*<\s*-0\.0001f\)\s*return\s+-MIN_DRIVE_DUTY;",
        feedforward,
    )
    assert re.search(r"return\s+0\.0f;", feedforward)
    assert "minimumDriveFeedforward(wheel.target_mps)" in update_pid
    assert re.search(
        r"output\s*=\s*minimumDriveFeedforward\(wheel\.target_mps\)\s*\+"
        r"\s*feedback",
        update_pid,
    )


def test_zero_wheel_target_clears_pid_state_and_returns_zero_output():
    update_pid = function_source("updatePid")

    measured = update_pid.find("wheel.measured_mps = travelled / dt;")
    zero_guard = re.search(
        r"if\s*\(fabs\(wheel\.target_mps\)\s*<=\s*0\.0001f\)\s*\{"
        r"\s*wheel\.integral\s*=\s*0\.0f;"
        r"\s*wheel\.prev_error\s*=\s*0\.0f;"
        r"\s*return\s+0\.0f;\s*\}",
        update_pid,
        re.S,
    )
    error_update = update_pid.find("float error = wheel.target_mps")

    assert measured >= 0
    assert zero_guard
    assert measured < zero_guard.start() < error_update


def test_wheel_direction_reversal_resets_pid_history_before_output():
    source = firmware_source()
    update_pid = function_source("updatePid")

    assert re.search(r"float\s+previous_target_mps\s*=\s*0\.0f", source)
    direction_change = re.search(
        r"bool\s+directionChanged\s*=\s*"
        r"wheel\.target_mps\s*\*\s*wheel\.previous_target_mps\s*<\s*0\.0f;"
        r".*?if\s*\(directionChanged\)\s*\{"
        r"\s*wheel\.integral\s*=\s*0\.0f;"
        r"\s*wheel\.prev_error\s*=\s*0\.0f;\s*\}",
        update_pid,
        re.S,
    )
    target_snapshot = update_pid.find(
        "wheel.previous_target_mps = wheel.target_mps;"
    )
    integral_update = update_pid.find("wheel.integral += error * dt;")

    assert direction_change
    assert direction_change.end() < target_snapshot < integral_update


def test_stop_and_watchdog_disable_pid_motor_output_until_velocity_command():
    source = firmware_source()
    stop_all = function_source("stopAll")
    handle_line = function_source("handleLine")
    control_loop = function_source("loop")

    assert re.search(r"bool\s+driveEnabled\s*=\s*false", source)
    assert "driveEnabled = false;" in stop_all
    assert "applyPwm(CH_L_R, CH_L_L, 0.0f);" in stop_all
    assert "applyPwm(CH_R_R, CH_R_L, 0.0f);" in stop_all

    assert re.search(
        r'if\s*\(payload\.startsWith\("S"\)\)\s*\{\s*stopAll\(\);',
        handle_line,
    )
    assert re.search(
        r"if\s*\(now\s*-\s*lastCommandMs\s*>\s*WATCHDOG_TIMEOUT_MS\)"
        r"\s*\{\s*stopAll\(\);\s*\}",
        control_loop,
    )

    zero_stop = re.search(
        r"if\s*\(fabs\(left\).*?fabs\(right\).*?\)\s*\{"
        r"\s*stopAll\(\);\s*lastCommandMs\s*=\s*millis\(\);\s*return;\s*\}",
        handle_line,
        re.S,
    )
    enable = handle_line.find("driveEnabled = true;")
    assert zero_stop
    assert enable > zero_stop.end()
    assert handle_line.count("driveEnabled = true;") == 1

    assert re.search(
        r"if\s*\(driveEnabled\)\s*\{.*?LEFT_MOTOR_OUTPUT_SIGN\s*\*\s*updatePid"
        r".*?RIGHT_MOTOR_OUTPUT_SIGN\s*\*\s*updatePid.*?\}\s*else\s*\{"
        r".*?applyPwm\(CH_L_R, CH_L_L, 0\.0f\)"
        r".*?applyPwm\(CH_R_R, CH_R_L, 0\.0f\)",
        control_loop,
        re.S,
    )


def test_malformed_velocity_frame_cannot_enable_drive():
    parser = function_source("parseFiniteFloat")
    handle_line = function_source("handleLine")

    assert "strtof(" in parser
    assert "start == end" in parser
    assert "*end != '\\0'" in parser
    assert "!isfinite(" in parser

    extra_field_check = re.search(
        r"if\s*\(payload\.indexOf\(',',\s*secondComma\s*\+\s*1\)\s*>=\s*0\)"
        r"\s*return;",
        handle_line,
    )
    left_validation = re.search(
        r"if\s*\(\s*!parseFiniteFloat\(leftText,\s*left\)\s*\)\s*return;",
        handle_line,
    )
    right_validation = re.search(
        r"if\s*\(\s*!parseFiniteFloat\(rightText,\s*right\)\s*\)\s*return;",
        handle_line,
    )
    enable = handle_line.find("driveEnabled = true;")

    assert extra_field_check
    assert left_validation
    assert right_validation
    assert enable > max(
        extra_field_check.end(), left_validation.end(), right_validation.end()
    )
