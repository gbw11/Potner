from __future__ import annotations

import pytest

from src.status.rules import evaluate_status


@pytest.fixture
def normal_reading() -> dict:
    return {
        "timestamp": "2026-07-27T10:00:00+09:00",
        "temperature_c": 22.0,
        "humidity_pct": 55.0,
        "light_lux": 400.0,
        "soil_raw": 14000,
        "soil_moisture_pct": 50.0,
    }


@pytest.fixture
def dry_soil_reading(normal_reading: dict) -> dict:
    reading = dict(normal_reading)
    reading["soil_moisture_pct"] = 25.0
    return reading


@pytest.fixture
def default_status_rules() -> dict:
    return {
        "soil_moisture_pct": {"low": 30, "high": 80},
        "temperature_c": {"low": 15, "high": 32},
        "humidity_pct": {"low": 30, "high": 80},
        "light_lux": {"low": 100, "high": 5000},
    }


@pytest.fixture
def plant_status(normal_reading: dict, default_status_rules: dict):
    return evaluate_status(normal_reading, default_status_rules)
