from __future__ import annotations

import logging
from typing import Any, Optional

from .base import Fan, WaterPump
from .fan import MockFan, MosfetFan
from .mock import MockWaterPump
from .pump import L298NWaterPump, RelayWaterPump

log = logging.getLogger(__name__)


def build_pump(config: dict[str, Any]) -> Optional[WaterPump]:
    pump_cfg = config.get("pump", {}) or {}
    if not pump_cfg.get("enabled", False):
        return None

    platform = str(config.get("platform", "mock")).lower()
    driver = str(pump_cfg.get("driver", "l298n")).lower()
    if platform == "mock":
        driver = "mock"

    try:
        flow = float(pump_cfg.get("flow_ml_per_sec", 25.0))
        max_run = float(pump_cfg.get("max_run_sec", 30.0))
        startup = float(pump_cfg.get("startup_sec", 0.0))
        min_run = float(pump_cfg.get("min_run_sec", 0.0))
    except (TypeError, ValueError) as exc:
        # 보정값이 숫자가 아니면 급수량 환산이 통째로 틀어지므로 펌프를 띄우지 않는다
        log.warning(f"pump 보정값이 숫자가 아니라 펌프를 비활성화합니다: {exc}")
        return None

    try:
        if driver in {"l298n", "hbridge"}:
            ena_pin = pump_cfg.get("ena_pin")
            return L298NWaterPump(
                in1_pin=int(pump_cfg.get("in1_pin", 17)),
                in2_pin=int(pump_cfg.get("in2_pin", 18)),
                ena_pin=None if ena_pin is None else int(ena_pin),
                speed=float(pump_cfg.get("speed", 1.0)),
                flow_ml_per_sec=flow,
                max_run_sec=max_run,
                startup_sec=startup,
                min_run_sec=min_run,
            )
        if driver == "relay":
            return RelayWaterPump(
                pin=int(pump_cfg.get("in1_pin", 17)),
                active_high=bool(pump_cfg.get("active_high", True)),
                flow_ml_per_sec=flow,
                max_run_sec=max_run,
                startup_sec=startup,
                min_run_sec=min_run,
            )
        if driver == "mock":
            return MockWaterPump(
                flow_ml_per_sec=flow,
                max_run_sec=max_run,
                startup_sec=startup,
                min_run_sec=min_run,
            )
        raise ValueError(f"Unknown pump driver: {driver}")
    except Exception as exc:  # noqa: BLE001 — 펌프 실패해도 다른 기능 유지
        log.warning(f"pump unavailable ({driver}): {exc}")
        return None


def build_fan(config: dict[str, Any]) -> Optional[Fan]:
    fan_cfg = config.get("fan", {})
    if not fan_cfg.get("enabled", False):
        return None

    platform = str(config.get("platform", "mock")).lower()
    driver = str(fan_cfg.get("driver", "mosfet")).lower()
    if platform == "mock":
        driver = "mock"

    try:
        if driver == "mosfet":
            return MosfetFan(
                pin=int(fan_cfg.get("pin", 23)),
                pwm_hz=float(fan_cfg.get("pwm_hz", 100.0)),
            )
        if driver == "mock":
            return MockFan()
        raise ValueError(f"Unknown fan driver: {driver}")
    except Exception as exc:  # noqa: BLE001 — 팬 실패해도 다른 기능 유지
        log.warning(f"fan unavailable ({driver}): {exc}")
        return None
