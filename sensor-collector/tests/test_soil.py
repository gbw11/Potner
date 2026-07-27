from __future__ import annotations

from src.sensors.soil import ADS1115SoilSensor


def _sensor(dry: int = 20000, wet: int = 8000) -> ADS1115SoilSensor:
    # smbus2 / I2C 없이 변환 로직만 검증
    sensor = object.__new__(ADS1115SoilSensor)
    sensor._dry_raw = dry
    sensor._wet_raw = wet
    return sensor


def test_midpoint_raw_is_about_50_percent():
    assert _sensor()._to_percent(14000) == 50.0


def test_dry_and_wet_raw_clamp():
    sensor = _sensor()
    assert sensor._to_percent(20000) == 0.0
    assert sensor._to_percent(8000) == 100.0
    assert sensor._to_percent(30000) == 0.0
    assert sensor._to_percent(0) == 100.0


def test_dry_equals_wet_returns_zero():
    assert _sensor(dry=10000, wet=10000)._to_percent(10000) == 0.0
