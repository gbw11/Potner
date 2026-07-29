from __future__ import annotations

from src.status.rules import evaluate_status


def test_normal_reading_is_ok(normal_reading, default_status_rules):
    status = evaluate_status(normal_reading, default_status_rules)

    assert status.soil.level == "normal"
    assert status.temperature.level == "normal"
    assert status.humidity.level == "normal"
    assert status.light.level == "normal"
    assert status.needs_attention is False
    assert "괜찮" in status.summary_ko


def test_dry_soil_needs_attention(dry_soil_reading, default_status_rules):
    status = evaluate_status(dry_soil_reading, default_status_rules)

    assert status.soil.level == "low"
    assert status.needs_attention is True
    assert "말라" in status.summary_ko


def test_boundary_values_count_as_normal(default_status_rules):
    reading = {
        "timestamp": "t",
        "soil_moisture_pct": 30,
        "temperature_c": 32,
        "humidity_pct": 30,
        "light_lux": 100,
    }
    status = evaluate_status(reading, default_status_rules)

    assert status.soil.level == "normal"
    assert status.temperature.level == "normal"
    assert status.humidity.level == "normal"
    assert status.light.level == "normal"


def test_missing_values_are_unknown(default_status_rules):
    status = evaluate_status({"timestamp": "t"}, default_status_rules)

    assert status.soil.level == "unknown"
    assert status.temperature.level == "unknown"
    assert status.needs_attention is False


def test_invalid_number_becomes_unknown(default_status_rules):
    status = evaluate_status(
        {"timestamp": "t", "soil_moisture_pct": "bad"},
        default_status_rules,
    )
    assert status.soil.level == "unknown"
