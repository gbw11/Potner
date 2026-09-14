from __future__ import annotations

from src.actuators import MockWaterPump
from src.mqtt.water_command import WaterCommandListener, _extract_ml


def _listener(pump: MockWaterPump | None = None) -> WaterCommandListener:
    return WaterCommandListener(
        pump=pump or MockWaterPump(flow_ml_per_sec=25.0),
        host="localhost",
        device_id="pi-test",
        username="u",
        password="p",
        command_topic="t/cmd",
        result_topic="t/result",
    )


def test_extract_ml_accepts_common_keys():
    assert _extract_ml({"ml": 150}) == 150.0
    assert _extract_ml({"amountMl": "80"}) == 80.0
    assert _extract_ml({"amount_ml": 50}) == 50.0
    assert _extract_ml({"other": 1}) is None
    assert _extract_ml({"ml": "abc"}) is None


def test_handle_command_dispenses_and_returns_ok():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    result = _listener(pump).handle_command({"ml": 100, "requestId": "req-1"})
    assert result["status"] == "OK"
    assert result["requestId"] == "req-1"
    assert result["requestedMl"] == 100.0
    assert result["dispensedMl"] == 100.0
    assert result["durationSec"] == 4.0
    assert result["capped"] is False
    assert pump.run_history == [4.0]
    assert result["deviceId"] == "pi-test"


def test_handle_command_rejects_invalid_ml():
    pump = MockWaterPump()
    listener = _listener(pump)
    for payload in ({"ml": -5}, {"ml": 0}, {}, {"ml": "abc"}):
        result = listener.handle_command(payload)
        assert result["status"] == "ERROR"
    assert pump.run_history == []


def test_handle_command_busy_when_lock_held():
    listener = _listener()
    assert listener._dispense_lock.acquire(blocking=False)
    try:
        result = listener.handle_command({"ml": 100})
        assert result["status"] == "BUSY"
    finally:
        listener._dispense_lock.release()
    # 락 해제 후에는 정상 처리
    assert listener.handle_command({"ml": 100})["status"] == "OK"


def test_handle_command_reports_capped():
    pump = MockWaterPump(flow_ml_per_sec=10.0, max_run_sec=5.0)
    result = _listener(pump).handle_command({"ml": 100})
    assert result["status"] == "OK"
    assert result["capped"] is True
    assert result["dispensedMl"] == 50.0
