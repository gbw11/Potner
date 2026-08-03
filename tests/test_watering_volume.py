"""급수량 계산 (ml ↔ 가동 시간 환산, 유량 보정) 테스트.

기존 tests/test_pump.py 가 기본 동작을 덮고, 여기서는 엣지 케이스와
보정값 갱신 흐름을 검증한다.
"""

from __future__ import annotations

import math

import pytest

from cli.water import Calibration, fit_calibration, update_pump_calibration
from src.actuators import MockWaterPump, build_pump

# --- ml → 시간 환산 ---


def test_plan_does_not_run_pump():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    plan = pump.plan_dispense(100)
    assert plan.run_sec == 4.0
    assert plan.expected_ml == 100.0
    assert pump.run_history == []  # 계획만 세우고 펌프는 건드리지 않는다


def test_reported_duration_matches_actual_run():
    # 나누어떨어지지 않는 요청도 보고값과 실제 가동값이 같아야 한다
    pump = MockWaterPump(flow_ml_per_sec=7.0, startup_sec=0.35)
    result = pump.dispense_ml(33)
    assert pump.run_history == [result.duration_sec]
    assert result.dispensed_ml == pytest.approx(pump.ml_for_duration(result.duration_sec), abs=0.05)


def test_round_trip_ml_duration():
    pump = MockWaterPump(flow_ml_per_sec=12.5, startup_sec=0.4)
    for ml in (1, 10, 100, 250):
        assert pump.ml_for_duration(pump.duration_for_ml(ml)) == pytest.approx(ml)


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf")])
def test_non_finite_ml_rejected(bad):
    # NaN 은 `ml <= 0` 검사를 통과해 버리므로 별도로 막아야 한다 (time.sleep(nan) 방지)
    pump = MockWaterPump()
    with pytest.raises(ValueError):
        pump.dispense_ml(bad)
    with pytest.raises(ValueError):
        pump.plan_dispense(bad)
    assert pump.run_history == []


def test_non_numeric_ml_rejected():
    pump = MockWaterPump(flow_ml_per_sec=25.0)
    with pytest.raises(ValueError):
        pump.dispense_ml("많이")
    with pytest.raises(ValueError):
        pump.dispense_ml(None)
    assert pump.run_history == []
    # 숫자 문자열은 관대하게 받는다 (MQTT 페이로드가 "80" 처럼 오는 경우)
    assert pump.dispense_ml("100").duration_sec == 4.0


def test_duration_for_ml_rejects_negative():
    pump = MockWaterPump()
    with pytest.raises(ValueError):
        pump.duration_for_ml(-1)


def test_run_for_rejects_non_finite():
    pump = MockWaterPump()
    with pytest.raises(ValueError):
        pump.run_for(float("nan"))
    assert pump.run_history == []


# --- min_run_sec 하한 ---


def test_tiny_request_raised_to_min_run_sec():
    # 0.04s 펄스로는 물이 안 나오는데 "1 ml 급수함"이라고 보고하던 문제
    pump = MockWaterPump(flow_ml_per_sec=25.0, min_run_sec=0.5)
    result = pump.dispense_ml(1)
    assert result.floored is True
    assert result.duration_sec == 0.5
    assert result.dispensed_ml == 12.5  # 실제로 나간 양을 정직하게 보고
    assert pump.run_history == [0.5]


def test_normal_request_not_floored():
    pump = MockWaterPump(flow_ml_per_sec=25.0, min_run_sec=0.5)
    result = pump.dispense_ml(100)
    assert result.floored is False
    assert result.duration_sec == 4.0


def test_cap_wins_over_floor():
    pump = MockWaterPump(flow_ml_per_sec=10.0, max_run_sec=5.0, min_run_sec=1.0)
    result = pump.dispense_ml(100)
    assert result.capped is True
    assert result.floored is False
    assert result.duration_sec == 5.0


def test_min_run_sec_validation():
    with pytest.raises(ValueError):
        MockWaterPump(min_run_sec=-1)
    with pytest.raises(ValueError):
        MockWaterPump(max_run_sec=2.0, min_run_sec=3.0)  # 하한 > 상한은 config 모순


def test_pump_rejects_non_finite_calibration():
    with pytest.raises(ValueError):
        MockWaterPump(flow_ml_per_sec=float("nan"))
    with pytest.raises(ValueError):
        MockWaterPump(max_run_sec=float("inf"))


# --- factory 를 통한 config 반영 ---


def test_build_pump_passes_min_run_sec():
    config = {
        "platform": "mock",
        "pump": {"enabled": True, "flow_ml_per_sec": 20.0, "min_run_sec": 0.4},
    }
    pump = build_pump(config)
    assert pump.min_run_sec == 0.4


def test_build_pump_returns_none_on_bad_calibration():
    # flow 가 0/문자열이면 환산이 통째로 틀어지므로 펌프를 만들지 않는다
    for bad in (0, -5, "빠름"):
        config = {"platform": "mock", "pump": {"enabled": True, "flow_ml_per_sec": bad}}
        assert build_pump(config) is None


# --- 유량 보정 피팅 ---


def test_fit_two_points():
    # 25 ml/s, 시동 손실 1s: 3s→50ml, 8s→175ml
    cal = fit_calibration([(3.0, 50.0), (8.0, 175.0)])
    assert cal.flow_ml_per_sec == 25.0
    assert cal.startup_sec == 1.0
    assert cal.warnings == ()


def test_fit_averages_repeated_measurements():
    # 같은 구간을 두 번씩 재고 한쪽이 조금 흔들려도 참값 근처로 수렴해야 한다
    cal = fit_calibration([(3.0, 74.0), (3.0, 76.0), (8.0, 199.0), (8.0, 201.0)])
    assert cal.flow_ml_per_sec == pytest.approx(25.0, abs=0.2)
    assert cal.startup_sec == pytest.approx(0.0, abs=0.15)


def test_fit_rejects_single_point():
    with pytest.raises(ValueError):
        fit_calibration([(3.0, 75.0)])


def test_fit_rejects_identical_durations():
    # 기울기 0 나눗셈으로 죽는 대신 사람이 읽을 수 있는 오류를 낸다
    with pytest.raises(ValueError):
        fit_calibration([(5.0, 100.0), (5.0, 110.0)])


def test_fit_rejects_non_increasing_volume():
    with pytest.raises(ValueError):
        fit_calibration([(3.0, 100.0), (8.0, 80.0)])


def test_fit_rejects_invalid_inputs():
    with pytest.raises(ValueError):
        fit_calibration([(0.0, 10.0), (5.0, 100.0)])
    with pytest.raises(ValueError):
        fit_calibration([(3.0, float("nan")), (8.0, 100.0)])


def test_fit_clamps_small_negative_startup():
    # 측정 오차로 startup 이 살짝 음수 → 0 으로 눌러 duration 이 짧아지지 않게
    cal = fit_calibration([(3.0, 80.0), (8.0, 205.0)])
    assert cal.startup_sec == 0.0
    assert cal.flow_ml_per_sec == 25.0


def test_fit_warns_on_suspicious_startup():
    cal = fit_calibration([(3.0, 20.0), (8.0, 145.0)])  # startup 2s 초과
    assert cal.startup_sec > 2.0
    assert any("startup_sec" in w for w in cal.warnings)


def test_fit_warns_on_out_of_range_flow():
    cal = fit_calibration([(3.0, 0.6), (8.0, 1.6)])  # 0.2 ml/s — 단위 착오 의심
    assert any("flow_ml_per_sec" in w for w in cal.warnings)


# --- 보정값 config 반영 ---


CONFIG_SAMPLE = """platform: mock

pump:
  enabled: true
  flow_ml_per_sec: 25.0  # cli.water calibrate 로 측정
  startup_sec: 0.0
  max_run_sec: 30

fan:
  flow_ml_per_sec: 999.0
"""


def test_update_pump_calibration_writes_values(tmp_path):
    path = tmp_path / "cfg.yaml"
    path.write_text(CONFIG_SAMPLE, encoding="utf-8")
    update_pump_calibration(path, Calibration(flow_ml_per_sec=31.25, startup_sec=0.42))

    text = path.read_text(encoding="utf-8")
    assert "  flow_ml_per_sec: 31.25  # cli.water calibrate 로 측정" in text  # 주석 보존
    assert "  startup_sec: 0.42" in text
    assert "  flow_ml_per_sec: 999.0" in text  # 다른 섹션은 건드리지 않음
    assert "  max_run_sec: 30" in text


def test_updated_config_round_trips_into_pump(tmp_path):
    path = tmp_path / "cfg.yaml"
    path.write_text(CONFIG_SAMPLE, encoding="utf-8")
    update_pump_calibration(path, Calibration(flow_ml_per_sec=10.0, startup_sec=0.5))

    from src.config import load_config

    pump = build_pump(load_config(path))
    assert pump.flow_ml_per_sec == 10.0
    assert pump.duration_for_ml(100) == 10.5


def test_update_pump_calibration_errors_when_key_missing(tmp_path):
    # 조용히 넘어가면 옛 보정값으로 계속 급수하게 된다
    path = tmp_path / "cfg.yaml"
    path.write_text("pump:\n  enabled: true\n", encoding="utf-8")
    with pytest.raises(ValueError):
        update_pump_calibration(path, Calibration(flow_ml_per_sec=20.0, startup_sec=0.1))


def test_calibration_result_matches_pump_math():
    cal = fit_calibration([(3.0, 50.0), (8.0, 175.0)])
    pump = MockWaterPump(
        flow_ml_per_sec=cal.flow_ml_per_sec, startup_sec=cal.startup_sec, max_run_sec=60.0
    )
    # 보정값대로면 100 ml 요청은 4s + 시동 1s = 5s
    assert pump.duration_for_ml(100) == 5.0
    assert math.isclose(pump.ml_for_duration(5.0), 100.0)
