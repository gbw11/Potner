"""과급수 방지 게이트 테스트.

시간에 의존하는 판정(간격/24시간 누적)은 가짜 시계를 주입해 검증한다.
"""

from __future__ import annotations

import json

import pytest

from src.actuators import MockWaterPump
from src.actuators.safety import SafetyLimits, WateringGuard, build_guard
from src.mqtt.water_command import WaterCommandListener


class FakeClock:
    def __init__(self, start: float = 1_000_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _guard(clock: FakeClock | None = None, **limit_kwargs) -> WateringGuard:
    return WateringGuard(SafetyLimits(**limit_kwargs), time_fn=clock or FakeClock())


# --- 1회 상한 ---


def test_allows_normal_request():
    decision = _guard(max_ml_per_dose=500).check(100)
    assert decision.allowed is True
    assert decision.code == "OK"
    assert decision.ml == 100.0
    assert decision.limited is False


def test_clamps_oversized_request():
    decision = _guard(max_ml_per_dose=200).check(5000)
    assert decision.allowed is True
    assert decision.code == "LIMITED"
    assert decision.ml == 200.0
    assert decision.limited is True
    assert "200" in decision.reason


@pytest.mark.parametrize("bad", [0, -10, float("nan"), float("inf"), "많이", None])
def test_rejects_invalid_amounts(bad):
    decision = _guard(max_ml_per_dose=500).check(bad)
    assert decision.allowed is False
    assert decision.code == "INVALID"


def test_no_limits_configured_passes_through():
    decision = _guard().check(9999)
    assert decision.allowed is True
    assert decision.ml == 9999.0


# --- 재급수 간격 ---


def test_blocks_second_dose_within_interval():
    clock = FakeClock()
    guard = _guard(clock, min_interval_sec=300)
    assert guard.check(100).allowed is True
    guard.record(100)

    clock.advance(60)
    decision = guard.check(100)
    assert decision.allowed is False
    assert decision.code == "TOO_SOON"

    clock.advance(300)
    assert guard.check(100).allowed is True


def test_interval_counts_from_actual_dose_not_check():
    # check 만 하고 급수하지 않았으면 간격 제한이 걸리면 안 된다
    clock = FakeClock()
    guard = _guard(clock, min_interval_sec=300)
    guard.check(100)
    assert guard.check(100).allowed is True


# --- 24시간 누적 상한 ---


def test_daily_budget_reduces_then_blocks():
    clock = FakeClock()
    guard = _guard(clock, daily_max_ml=250)
    guard.record(200)

    decision = guard.check(100)
    assert decision.allowed is True
    assert decision.ml == 50.0  # 잔여 50 ml 만
    assert decision.code == "LIMITED"

    guard.record(50)
    blocked = guard.check(100)
    assert blocked.allowed is False
    assert blocked.code == "DAILY_LIMIT"


def test_daily_budget_rolls_off_after_24h():
    clock = FakeClock()
    guard = _guard(clock, daily_max_ml=100)
    guard.record(100)
    assert guard.check(50).allowed is False

    clock.advance(24 * 3600 + 1)
    assert guard.used_ml() == 0.0
    assert guard.check(50).allowed is True


def test_records_actual_dispensed_not_requested():
    clock = FakeClock()
    guard = _guard(clock, daily_max_ml=1000)
    pump = MockWaterPump(flow_ml_per_sec=10.0, max_run_sec=5.0)
    result = pump.dispense_ml(100)  # 상한에 걸려 50 ml 만 나감
    guard.record(result.dispensed_ml)
    assert guard.used_ml() == 50.0


def test_clock_jump_backwards_does_not_lock_out():
    # NTP 보정으로 시계가 뒤로 튀어도 미래 기록 때문에 영영 막히면 안 된다
    clock = FakeClock()
    guard = _guard(clock, min_interval_sec=300, daily_max_ml=100)
    guard.record(100)
    clock.advance(-10_000)
    assert guard.used_ml() == 0.0
    assert guard.check(50).allowed is True


# --- 토양수분 게이트 ---


def test_skips_when_soil_already_wet():
    guard = WateringGuard(
        SafetyLimits(soil_wet_pct=70), soil_provider=lambda: 85.0, time_fn=FakeClock()
    )
    decision = guard.check(100)
    assert decision.allowed is False
    assert decision.code == "SOIL_WET"
    assert "85" in decision.reason


def test_allows_when_soil_dry():
    guard = WateringGuard(
        SafetyLimits(soil_wet_pct=70), soil_provider=lambda: 30.0, time_fn=FakeClock()
    )
    assert guard.check(100).allowed is True


def test_missing_soil_sensor_is_fail_open():
    # 센서가 없거나 None 을 주면 이 축만 비활성 — 급수는 계속 가능해야 한다
    for provider in (None, lambda: None):
        guard = WateringGuard(
            SafetyLimits(soil_wet_pct=70), soil_provider=provider, time_fn=FakeClock()
        )
        assert guard.check(100).allowed is True


def test_soil_read_failure_is_fail_open():
    def broken():
        raise OSError("i2c read failed")

    guard = WateringGuard(
        SafetyLimits(soil_wet_pct=70), soil_provider=broken, time_fn=FakeClock()
    )
    assert guard.check(100).allowed is True


def test_soil_gate_ignored_when_threshold_unset():
    guard = WateringGuard(SafetyLimits(), soil_provider=lambda: 99.0, time_fn=FakeClock())
    assert guard.check(100).allowed is True


# --- 상태 영속 ---


def test_history_survives_restart(tmp_path):
    clock = FakeClock()
    path = tmp_path / "water_history.json"
    first = WateringGuard(
        SafetyLimits(daily_max_ml=150), state_path=path, time_fn=clock
    )
    first.record(150)

    # 프로세스 재시작 상황: 같은 파일로 새 가드
    second = WateringGuard(
        SafetyLimits(daily_max_ml=150), state_path=path, time_fn=clock
    )
    assert second.used_ml() == 150.0
    assert second.check(50).code == "DAILY_LIMIT"


def test_corrupt_state_file_does_not_block_watering(tmp_path):
    path = tmp_path / "water_history.json"
    path.write_text("{not json", encoding="utf-8")
    guard = WateringGuard(SafetyLimits(daily_max_ml=150), state_path=path, time_fn=FakeClock())
    assert guard.used_ml() == 0.0
    assert guard.check(100).allowed is True


def test_state_file_prunes_old_entries(tmp_path):
    clock = FakeClock()
    path = tmp_path / "water_history.json"
    stale = clock.now - (30 * 3600)
    path.write_text(json.dumps({"doses": [[stale, 900.0]]}), encoding="utf-8")
    guard = WateringGuard(SafetyLimits(daily_max_ml=1000), state_path=path, time_fn=clock)
    assert guard.used_ml() == 0.0


# --- config → 가드 ---


def test_build_guard_disabled_returns_none():
    assert build_guard({"pump": {"safety": {"enabled": False}}}) is None
    assert build_guard({"pump": {}}) is None
    assert build_guard({}) is None


def test_build_guard_reads_limits():
    guard = build_guard(
        {
            "pump": {
                "safety": {
                    "enabled": True,
                    "max_ml_per_dose": 400,
                    "min_interval_sec": 120,
                    "daily_max_ml": 800,
                    "soil_wet_pct": 65,
                }
            }
        }
    )
    assert guard.limits.max_ml_per_dose == 400.0
    assert guard.limits.min_interval_sec == 120.0
    assert guard.limits.daily_max_ml == 800.0
    assert guard.limits.soil_wet_pct == 65.0


def test_build_guard_treats_null_and_zero_as_no_limit():
    guard = build_guard(
        {
            "pump": {
                "safety": {
                    "enabled": True,
                    "max_ml_per_dose": None,
                    "min_interval_sec": 0,
                    "daily_max_ml": "",
                    "soil_wet_pct": "이상함",
                }
            }
        }
    )
    assert guard.limits.max_ml_per_dose is None
    assert guard.limits.min_interval_sec == 0.0
    assert guard.limits.daily_max_ml is None
    assert guard.limits.soil_wet_pct is None
    assert guard.check(10_000).allowed is True


def test_shipped_config_enables_guard():
    from src.config import load_config

    guard = build_guard(load_config("config/raspberry_pi.yaml"))
    assert guard is not None
    assert guard.limits.max_ml_per_dose is not None
    assert guard.limits.daily_max_ml is not None


# --- MQTT 리스너 연동 ---


def _listener(pump: MockWaterPump, guard: WateringGuard | None) -> WaterCommandListener:
    return WaterCommandListener(
        pump=pump,
        host="localhost",
        device_id="pi-test",
        username="u",
        password="p",
        command_topic="t/cmd",
        result_topic="t/result",
        guard=guard,
    )


def test_listener_skips_when_guard_blocks():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    guard = WateringGuard(
        SafetyLimits(soil_wet_pct=70), soil_provider=lambda: 90.0, time_fn=FakeClock()
    )
    result = _listener(pump, guard).handle_command({"ml": 100, "requestId": "r1"})
    assert result["status"] == "SKIPPED"
    assert result["skipCode"] == "SOIL_WET"
    assert result["dispensedMl"] == 0.0
    assert result["requestedMl"] == 100.0
    assert result["requestId"] == "r1"
    assert pump.run_history == []  # 펌프는 돌지 않았다


def test_listener_reports_limited_dose():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    guard = _guard(max_ml_per_dose=200)
    result = _listener(pump, guard).handle_command({"ml": 1000})
    assert result["status"] == "OK"
    assert result["limited"] is True
    assert result["requestedMl"] == 1000.0
    assert result["dispensedMl"] == 200.0
    assert pump.run_history == [8.0]


def test_listener_records_dose_so_repeat_command_is_blocked():
    clock = FakeClock()
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    listener = _listener(pump, _guard(clock, min_interval_sec=300))

    assert listener.handle_command({"ml": 100})["status"] == "OK"
    assert listener.handle_command({"ml": 100})["status"] == "SKIPPED"
    assert pump.run_history == [4.0]  # 두 번째 명령으로는 펌프가 돌지 않음

    clock.advance(301)
    assert listener.handle_command({"ml": 100})["status"] == "OK"


def test_listener_without_guard_still_works():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    result = _listener(pump, None).handle_command({"ml": 100})
    assert result["status"] == "OK"
    assert "limited" not in result
    assert "floored" not in result


def test_listener_flags_floored_dose():
    pump = MockWaterPump(flow_ml_per_sec=25.0, min_run_sec=0.5)
    result = _listener(pump, None).handle_command({"ml": 2})
    assert result["status"] == "OK"
    assert result["floored"] is True
    assert result["dispensedMl"] > result["requestedMl"]


@pytest.mark.parametrize("payload", [{"ml": float("nan")}, {"ml": float("inf")}, {"ml": True}])
def test_listener_rejects_non_finite_payload(payload):
    # json.loads 는 NaN/Infinity 를 그대로 파싱한다 — 펌프까지 내려가면 안 된다
    pump = MockWaterPump()
    result = _listener(pump, None).handle_command(payload)
    assert result["status"] == "ERROR"
    assert pump.run_history == []
