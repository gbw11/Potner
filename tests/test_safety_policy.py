import math

import pytest

from potner_mission.safety_policy import (
    DirectionalSafety,
    SafetyConfig,
    SectorClearance,
    inputs_fresh,
    observe_scan,
)


def clear_scan(**overrides):
    values = {
        "front": 1.0,
        "rear": 1.0,
        "left": 1.0,
        "right": 1.0,
        "front_samples": 8,
        "rear_samples": 8,
        "left_samples": 8,
        "right_samples": 8,
    }
    values.update(overrides)
    return SectorClearance(**values)


def ready_safety(**config_overrides):
    safety = DirectionalSafety(SafetyConfig(**config_overrides))
    safety.update_scan(clear_scan())
    return safety


def test_front_wall_blocks_forward_but_preserves_safe_left_turn():
    safety = ready_safety()
    safety.update_scan(clear_scan(front=0.20))

    result = safety.filter(linear_x=0.08, angular_z=0.30)

    assert result.linear_x == 0.0
    assert result.angular_z == pytest.approx(0.30)
    assert result.limited
    assert "front" in result.reasons


def test_front_wall_does_not_prevent_reverse_when_rear_is_clear():
    safety = ready_safety()
    safety.update_scan(clear_scan(front=0.20, rear=0.80))

    result = safety.filter(linear_x=-0.05, angular_z=0.0)

    assert result.linear_x == pytest.approx(-0.05)
    assert not result.limited


def test_rear_wall_blocks_reverse_without_blocking_forward():
    safety = ready_safety()
    safety.update_scan(clear_scan(rear=0.20))

    reverse = safety.filter(linear_x=-0.05, angular_z=0.0)
    forward = safety.filter(linear_x=0.05, angular_z=0.0)

    assert reverse.linear_x == 0.0
    assert "rear" in reverse.reasons
    assert forward.linear_x == pytest.approx(0.05)


@pytest.mark.parametrize(
    "sector,angular_z,allowed_angular",
    [
        ("left", 0.30, 0.0),
        ("left", -0.30, -0.30),
        ("right", -0.30, 0.0),
        ("right", 0.30, 0.30),
    ],
)
def test_side_wall_only_blocks_rotation_toward_that_side(
    sector, angular_z, allowed_angular
):
    safety = ready_safety()
    safety.update_scan(clear_scan(**{sector: 0.20}))

    result = safety.filter(linear_x=0.0, angular_z=angular_z)

    assert result.angular_z == pytest.approx(allowed_angular)


def test_left_tof_blocks_forward_and_left_turn_but_allows_escape_motion():
    safety = ready_safety()
    safety.update_bumper("left", 0.08)

    toward = safety.filter(linear_x=0.05, angular_z=0.30)
    escape = safety.filter(linear_x=-0.05, angular_z=-0.30)

    assert toward.linear_x == 0.0
    assert toward.angular_z == 0.0
    assert set(toward.reasons) == {"front_tof", "left_tof"}
    assert escape.linear_x == pytest.approx(-0.05)
    assert escape.angular_z == pytest.approx(-0.30)


def test_blocked_sector_uses_hysteresis_before_releasing():
    safety = ready_safety(stop_distance=0.25, clear_hysteresis=0.08)
    safety.update_scan(clear_scan(front=0.24))
    assert safety.filter(0.05, 0.0).linear_x == 0.0

    safety.update_scan(clear_scan(front=0.30))
    assert safety.filter(0.05, 0.0).linear_x == 0.0

    safety.update_scan(clear_scan(front=0.34))
    assert safety.filter(0.05, 0.0).linear_x == pytest.approx(0.05)


def test_missing_rear_measurements_fail_safe_without_stopping_forward():
    safety = ready_safety()
    safety.update_scan(clear_scan(rear=math.inf, rear_samples=0))

    reverse = safety.filter(-0.05, 0.0)
    forward = safety.filter(0.05, 0.0)

    assert reverse.linear_x == 0.0
    assert "rear_unobserved" in reverse.reasons
    assert forward.linear_x == pytest.approx(0.05)


def test_scan_angles_are_classified_into_four_directional_sectors():
    # -180, -90, 0, +90 degrees: rear, right, front, left
    observed = observe_scan(
        ranges=[0.60, 0.40, 0.20, 0.30],
        angle_min=-math.pi,
        angle_increment=math.pi / 2.0,
        range_min=0.05,
        range_max=8.0,
        front_arc_deg=60.0,
        rear_arc_deg=60.0,
        side_arc_deg=60.0,
    )

    assert observed.front == pytest.approx(0.20)
    assert observed.rear == pytest.approx(0.60)
    assert observed.left == pytest.approx(0.30)
    assert observed.right == pytest.approx(0.40)
    assert (
        observed.front_samples,
        observed.rear_samples,
        observed.left_samples,
        observed.right_samples,
    ) == (1, 1, 1, 1)


def test_diagonal_scan_point_is_covered_by_wide_side_sector():
    observed = observe_scan(
        ranges=[0.20],
        angle_min=math.pi / 4.0,
        angle_increment=0.01,
        range_min=0.05,
        range_max=8.0,
        front_arc_deg=60.0,
        rear_arc_deg=60.0,
        side_arc_deg=120.0,
    )

    assert observed.left == pytest.approx(0.20)
    assert observed.left_samples == 1


@pytest.mark.parametrize(
    "kwargs",
    [
        {"stop_distance": 0.0},
        {"bumper_stop_distance": -0.1},
        {"clear_hysteresis": -0.01},
        {"min_valid_samples": 0},
    ],
)
def test_invalid_safety_configuration_is_rejected(kwargs):
    with pytest.raises(ValueError):
        SafetyConfig(**kwargs)


@pytest.mark.parametrize(
    "scan_stamp,command_stamp,expected",
    [
        (9.8, 9.8, True),
        (None, 9.8, False),
        (9.8, None, False),
        (9.4, 9.8, False),
        (9.8, 9.4, False),
    ],
)
def test_final_motion_requires_fresh_scan_and_command(
    scan_stamp, command_stamp, expected
):
    assert inputs_fresh(
        now=10.0,
        scan_stamp=scan_stamp,
        command_stamp=command_stamp,
        scan_timeout=0.5,
        command_timeout=0.5,
    ) is expected
