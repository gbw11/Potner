from __future__ import annotations

import pytest

from src.actuators import MockFan, build_fan


def test_set_speed_clamps_range():
    fan = MockFan()
    assert fan.set_speed(150) == 100.0
    assert fan.set_speed(-10) == 0.0
    assert fan.set_speed(62.5) == 62.5
    assert fan.speed_history == [100.0, 0.0, 62.5]


def test_on_off():
    fan = MockFan()
    fan.on(80)
    assert fan.speed_pct == 80.0
    fan.off()
    assert fan.speed_pct == 0.0


def test_run_for_sets_then_stops():
    fan = MockFan()
    fan.run_for(0.01, 70)
    assert fan.speed_history == [70.0, 0.0]
    assert fan.speed_pct == 0.0


def test_run_for_rejects_non_positive_seconds():
    fan = MockFan()
    with pytest.raises(ValueError):
        fan.run_for(0)


def test_build_fan_mock_platform_forces_mock_driver():
    config = {"platform": "mock", "fan": {"enabled": True, "driver": "mosfet"}}
    assert isinstance(build_fan(config), MockFan)


def test_build_fan_disabled_returns_none():
    assert build_fan({"platform": "mock", "fan": {"enabled": False}}) is None
    assert build_fan({"platform": "mock"}) is None
