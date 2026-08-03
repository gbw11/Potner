"""수위 센서의 수집 루프·MQTT 텔레메트리 통합 테스트 — 하드웨어 불필요.

factory(SensorBundle.water_level) → collector(row.water_present)
→ publisher(WATER_LEVEL 텔레메트리) 경로와 config 계약을 검증한다.
실 GPIO 는 없으므로 raspberry_pi 경로는 가짜 gpiozero 를 sys.modules 에 주입한다
(tests/test_water_level.py 와 같은 방식).
"""

from __future__ import annotations

import sys
import types

import pytest

from src.collector import Collector
from src.mqtt.publisher import MqttTelemetryPublisher, build_mqtt_publisher
from src.sensors.factory import build_sensors
from src.sensors.water_level import MockFloatSwitchSensor
from src.storage import CsvStorage


class FakeButton:
    """gpiozero.Button 대역 — 생성 인자를 기록한다."""

    instances: list["FakeButton"] = []

    def __init__(self, pin, pull_up=None, bounce_time=None):
        self.pin = pin
        self.pull_up = pull_up
        self.bounce_time = bounce_time
        self.is_pressed = False
        FakeButton.instances.append(self)

    def close(self):
        pass


@pytest.fixture
def fake_gpiozero(monkeypatch):
    FakeButton.instances = []
    module = types.ModuleType("gpiozero")
    module.Button = FakeButton
    monkeypatch.setitem(sys.modules, "gpiozero", module)
    return module


def _mock_config(tmp_path, water_level: dict | None = None) -> dict:
    config = {
        "platform": "mock",
        "storage": {"path": str(tmp_path / "readings.csv")},
        "sensors": {
            "climate": {"enabled": True},
            "light": {"enabled": False},
            "soil": {"enabled": False},
        },
        "camera": {"enabled": False},
    }
    if water_level is not None:
        config["sensors"]["water_level"] = water_level
    return config


# --- factory ---------------------------------------------------------------


def test_factory_mock_platform_enabled_builds_mock_sensor(tmp_path):
    config = _mock_config(tmp_path, water_level={"enabled": True})
    bundle = build_sensors(config)
    assert isinstance(bundle.water_level, MockFloatSwitchSensor)


def test_factory_mock_platform_disabled_is_none(tmp_path):
    config = _mock_config(tmp_path, water_level={"enabled": False})
    assert build_sensors(config).water_level is None


def test_factory_missing_section_defaults_off(tmp_path):
    """sensors.water_level 섹션이 없으면 기본 꺼짐 (기존 config 호환)."""
    config = _mock_config(tmp_path)
    assert build_sensors(config).water_level is None


def test_factory_raspberry_pi_passes_pin_and_invert(fake_gpiozero):
    config = {
        "platform": "raspberry_pi",
        "sensors": {
            "climate": {"enabled": False},
            "light": {"enabled": False},
            "soil": {"enabled": False},
            "water_level": {"enabled": True, "pin": 22, "invert": False},
        },
    }
    bundle = build_sensors(config)

    sensor = bundle.water_level
    assert sensor is not None
    assert sensor.pin == 22
    button = FakeButton.instances[-1]
    assert button.pin == 22
    assert button.pull_up is True
    # invert=False: 접점 닫힘 = 물 있음
    button.is_pressed = True
    assert sensor.read().water_present is True


def test_factory_raspberry_pi_failure_warns_and_returns_none(monkeypatch):
    """gpiozero 없음 등 생성 실패는 다른 센서처럼 warn 후 None (수집 루프 유지)."""
    monkeypatch.setitem(sys.modules, "gpiozero", None)  # import 시 실패 유도
    config = {
        "platform": "raspberry_pi",
        "sensors": {
            "climate": {"enabled": False},
            "light": {"enabled": False},
            "soil": {"enabled": False},
            "water_level": {"enabled": True},
        },
    }
    assert build_sensors(config).water_level is None


# --- collector ---------------------------------------------------------------


def test_read_once_row_has_water_present(tmp_path):
    config = _mock_config(tmp_path, water_level={"enabled": True, "invert": False})
    collector = Collector(config)
    try:
        row = collector.read_once()
    finally:
        collector.close()

    assert "water_present" in row
    # MockFloatSwitchSensor 기본 values=[True](닫힘), invert=False → 물 있음
    assert row["water_present"] is True


def test_read_once_without_sensor_water_present_is_none(tmp_path):
    """enabled: false 여도 컬럼은 존재하되 값은 None."""
    config = _mock_config(tmp_path, water_level={"enabled": False})
    collector = Collector(config)
    try:
        row = collector.read_once()
    finally:
        collector.close()

    assert "water_present" in row
    assert row["water_present"] is None


def test_read_once_sensor_error_becomes_none(tmp_path):
    class BrokenSensor:
        def read_stable(self, **_kwargs):
            raise RuntimeError("GPIO busy")

        def close(self):
            pass

    config = _mock_config(tmp_path, water_level={"enabled": True})
    collector = Collector(config)
    collector.sensors.water_level = BrokenSensor()
    try:
        row = collector.read_once()
    finally:
        collector.close()

    assert row["water_present"] is None


def test_collector_uses_read_stable_not_read(tmp_path):
    """수집 경로는 출렁임 흡수를 위해 read() 가 아닌 read_stable() 을 쓴다."""
    calls: list[str] = []

    class SpySensor(MockFloatSwitchSensor):
        def read_stable(self, **kwargs):
            calls.append("read_stable")
            return super().read_stable(**kwargs)

    config = _mock_config(tmp_path, water_level={"enabled": True})
    collector = Collector(config)
    collector.sensors.water_level = SpySensor(invert=False)
    try:
        collector.read_once()
    finally:
        collector.close()

    assert calls == ["read_stable"]


def test_close_closes_water_level_sensor(tmp_path):
    closed: list[bool] = []

    class ClosableSensor(MockFloatSwitchSensor):
        def close(self):
            closed.append(True)

    config = _mock_config(tmp_path, water_level={"enabled": True})
    collector = Collector(config)
    collector.sensors.water_level = ClosableSensor()
    collector.close()

    assert closed == [True]


# --- storage ---------------------------------------------------------------


def test_storage_fieldnames_include_water_present_after_soil():
    idx_soil = CsvStorage.FIELDNAMES.index("soil_moisture_pct")
    assert CsvStorage.FIELDNAMES[idx_soil + 1] == "water_present"


# --- publisher ---------------------------------------------------------------


def _publisher(**kwargs) -> MqttTelemetryPublisher:
    return MqttTelemetryPublisher(
        host="127.0.0.1",
        device_id="raspberry-01",
        enabled=False,
        **kwargs,
    )


def test_build_messages_water_present_true_is_one():
    messages = _publisher().build_messages(
        {"temperature_c": 23.4, "humidity_pct": 51.2, "water_present": True}
    )

    assert len(messages) == 3
    types_ = [p["sensorType"] for _, p in messages]
    assert types_ == ["TEMPERATURE", "HUMIDITY", "WATER_LEVEL"]
    water = messages[-1][1]
    assert water["value"] == 1.0
    assert water["unit"] == "BOOLEAN"


def test_build_messages_water_present_false_is_zero():
    messages = _publisher().build_messages(
        {"temperature_c": None, "humidity_pct": None, "water_present": False}
    )

    assert len(messages) == 1
    payload = messages[0][1]
    assert payload["sensorType"] == "WATER_LEVEL"
    assert payload["value"] == 0.0
    assert payload["unit"] == "BOOLEAN"
    assert payload["deviceId"] == "raspberry-01"


def test_build_messages_water_present_none_is_skipped():
    messages = _publisher().build_messages(
        {"temperature_c": 20.0, "humidity_pct": 40.0, "water_present": None}
    )
    types_ = [p["sensorType"] for _, p in messages]
    assert types_ == ["TEMPERATURE", "HUMIDITY"]


def test_build_messages_telemetry_off_skips_water_level():
    publisher = _publisher(water_level_telemetry=False)
    messages = publisher.build_messages(
        {"temperature_c": 20.0, "humidity_pct": 40.0, "water_present": True}
    )
    types_ = [p["sensorType"] for _, p in messages]
    assert types_ == ["TEMPERATURE", "HUMIDITY"]


def test_build_messages_type_and_unit_from_config():
    """서버 SensorType enum 이 다르면 config 만 바꿔도 되는지 확인."""
    publisher = _publisher(
        water_level_type="WATER_TANK_LEVEL",
        water_level_unit="BOOL",
    )
    messages = publisher.build_messages({"water_present": True})

    payload = messages[0][1]
    assert payload["sensorType"] == "WATER_TANK_LEVEL"
    assert payload["unit"] == "BOOL"


def test_build_mqtt_publisher_reads_water_level_config(monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)
    publisher = build_mqtt_publisher(
        {
            "mqtt": {"enabled": False, "host": "broker.example", "device_id": "raspberry-01"},
            "sensors": {
                "water_level": {
                    "enabled": True,
                    "telemetry": False,
                    "telemetry_type": "TANK_LEVEL",
                    "telemetry_unit": "FLAG",
                }
            },
        }
    )
    assert publisher.water_level_telemetry is False
    assert publisher.water_level_type == "TANK_LEVEL"
    assert publisher.water_level_unit == "FLAG"


def test_build_mqtt_publisher_defaults_without_section(monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)
    publisher = build_mqtt_publisher(
        {"mqtt": {"enabled": False, "host": "broker.example", "device_id": "raspberry-01"}}
    )
    assert publisher.water_level_telemetry is True
    assert publisher.water_level_type == "WATER_LEVEL"
    assert publisher.water_level_unit == "BOOLEAN"


# --- config 계약 (tests/test_brightness_check.py 의 yaml 계약 테스트 선례) -----


def test_project_yaml_water_level_section_loads():
    from src.config import load_config

    expected_enabled = {
        "config/default.yaml": False,  # mock PC 기본 꺼짐
        "config/raspberry_pi.yaml": True,  # 실기 기본 켜짐
    }
    for path, enabled in expected_enabled.items():
        cfg = load_config(path)
        section = cfg["sensors"]["water_level"]
        assert section["enabled"] is enabled, f"{path}: enabled 는 {enabled} 이어야 한다"
        assert section["driver"] == "float_switch"
        assert section["pin"] == 27  # BCM GPIO27 (물리 13번)
        # 실물 확인(2026-08-03): 접점 닫힘 = 물 없음 → invert 는 True 여야 한다
        assert section["invert"] is True, f"{path}: invert 실측값이 바뀌었는지 확인"
        assert section["telemetry"] is True
        assert section["telemetry_type"] == "WATER_LEVEL"
        assert section["telemetry_unit"] == "BOOLEAN"
