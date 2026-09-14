from __future__ import annotations

import pytest

from src.actuators import MockWaterPump, build_pump


def test_duration_for_ml():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    assert pump.duration_for_ml(100) == 4.0


def test_dispense_records_run_and_stops():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    result = pump.dispense_ml(100)
    assert result.dispensed_ml == 100.0
    assert result.duration_sec == 4.0
    assert result.capped is False
    assert pump.run_history == [4.0]
    assert pump.running is False


def test_dispense_capped_by_max_run_sec():
    pump = MockWaterPump(flow_ml_per_sec=10.0, max_run_sec=5.0)
    result = pump.dispense_ml(100)  # 10s 필요하지만 상한 5s
    assert result.capped is True
    assert result.duration_sec == 5.0
    assert result.dispensed_ml == 50.0


def test_dispense_rejects_non_positive_ml():
    pump = MockWaterPump()
    with pytest.raises(ValueError):
        pump.dispense_ml(0)
    with pytest.raises(ValueError):
        pump.dispense_ml(-10)


def test_run_for_applies_max_run_sec():
    pump = MockWaterPump(max_run_sec=3.0)
    pump.run_for(10)
    assert pump.run_history == [3.0]


def test_invalid_flow_rate_rejected():
    with pytest.raises(ValueError):
        MockWaterPump(flow_ml_per_sec=0)
    with pytest.raises(ValueError):
        MockWaterPump(startup_sec=-1)


def test_startup_sec_added_to_duration():
    pump = MockWaterPump(flow_ml_per_sec=25.0, startup_sec=0.5)
    assert pump.duration_for_ml(100) == 4.5
    assert pump.ml_for_duration(4.5) == 100.0
    # 시동 손실 이하로 짧게 돌면 급수량 0
    assert pump.ml_for_duration(0.3) == 0.0


def test_dispense_with_startup_reports_requested_ml():
    pump = MockWaterPump(flow_ml_per_sec=25.0, startup_sec=0.5)
    result = pump.dispense_ml(100)
    assert result.duration_sec == 4.5
    assert result.dispensed_ml == 100.0
    assert result.capped is False


def test_dispense_capped_accounts_for_startup():
    pump = MockWaterPump(flow_ml_per_sec=10.0, max_run_sec=5.0, startup_sec=1.0)
    result = pump.dispense_ml(100)  # 11s 필요하지만 상한 5s
    assert result.capped is True
    assert result.duration_sec == 5.0
    assert result.dispensed_ml == 40.0  # (5-1)*10


def test_build_pump_mock_platform_forces_mock_driver():
    config = {
        "platform": "mock",
        "pump": {"enabled": True, "driver": "hbridge", "flow_ml_per_sec": 30.0},
    }
    pump = build_pump(config)
    assert isinstance(pump, MockWaterPump)
    assert pump.flow_ml_per_sec == 30.0


def test_build_pump_disabled_returns_none():
    assert build_pump({"platform": "mock", "pump": {"enabled": False}}) is None
    assert build_pump({"platform": "mock"}) is None
