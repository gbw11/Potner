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


def test_factory_mock_defaults_to_water_absent(tmp_path):
    """mock 기본값은 raw 접점 닫힘 + invert=True → '물 없음' 고정 (기존 동작 보존)."""
    config = _mock_config(tmp_path, water_level={"enabled": True})
    sensor = build_sensors(config).water_level

    assert sensor.read().water_present is False
    assert sensor.read().water_present is False  # 마지막 값 유지


def test_factory_mock_values_replay_sequence(tmp_path):
    """mock_values 로 물있음→물없음 흐름을 PC 에서 재현할 수 있어야 한다.

    루프 2 에서 발견: 이 배선이 없으면 mock 은 '물 없음' 고정이라
    물부족→복구(hold_sec 게이트) 를 PC 에서 시뮬레이션할 방법이 없었다.
    """
    config = _mock_config(
        tmp_path,
        # raw 접점 시퀀스. invert=True 라 closed=False 가 '물 있음'
        water_level={"enabled": True, "mock_values": [False, False, True]},
    )
    sensor = build_sensors(config).water_level

    assert sensor.read().water_present is True   # 물 있음
    assert sensor.read().water_present is True
    assert sensor.read().water_present is False  # 물 없음으로 전환
    assert sensor.read().water_present is False  # 마지막 값 유지


def test_factory_mock_toggle_alternates(tmp_path):
    """mock_toggle 은 읽을 때마다 접점을 교대시킨다 (잔떨림 재현용)."""
    config = _mock_config(
        tmp_path, water_level={"enabled": True, "mock_toggle": True}
    )
    sensor = build_sensors(config).water_level

    first = sensor.read().water_present
    second = sensor.read().water_present
    assert first != second


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
    # 대부분의 테스트는 hold 게이트와 무관한 페이로드 형태를 확인하므로 기본 0(즉시 확정).
    # hold_sec 자체를 검증하는 테스트는 명시적으로 오버라이드한다.
    kwargs.setdefault("water_low_hold_sec", 0.0)
    return MqttTelemetryPublisher(
        host="127.0.0.1",
        device_id="raspberry-01",
        enabled=False,
        **kwargs,
    )


def test_water_present_true_reports_water_low_false():
    """물 있음 → waterLow=false 도 발행한다 (서버 플래그 해제용, 규약 요구사항)."""
    messages = _publisher().build_messages(
        {"temperature_c": 23.4, "humidity_pct": 51.2, "water_present": True}
    )

    assert len(messages) == 3
    topic, payload = messages[-1]
    assert topic == "potner/device/raspberry-01/status/water-low"
    assert payload["waterLow"] is False
    assert payload["deviceId"] == "raspberry-01"
    assert set(payload) == {"messageId", "deviceId", "waterLow", "measuredAt"}


def test_water_present_false_reports_water_low_true():
    messages = _publisher().build_messages(
        {"temperature_c": None, "humidity_pct": None, "water_present": False}
    )

    assert len(messages) == 1
    topic, payload = messages[0]
    assert topic.endswith("/status/water-low")  # 텔레메트리 토픽이 아니다
    assert payload["waterLow"] is True
    assert "sensorType" not in payload  # WaterLowMessage 는 텔레메트리 스키마가 아님


def test_build_messages_water_present_none_is_skipped():
    """센서 없음/실패(None)는 미발행 — waterLow=false(물 있음)로 오인시키지 않는다."""
    messages = _publisher().build_messages(
        {"temperature_c": 20.0, "humidity_pct": 40.0, "water_present": None}
    )
    assert all(not topic.endswith("/status/water-low") for topic, _ in messages)
    assert len(messages) == 2


def test_build_messages_report_off_skips_water_low():
    publisher = _publisher(water_low_enabled=False)
    messages = publisher.build_messages(
        {"temperature_c": 20.0, "humidity_pct": 40.0, "water_present": True}
    )
    assert all(not topic.endswith("/status/water-low") for topic, _ in messages)


def test_water_low_topic_override():
    """토픽은 mqtt.water_low_topic 으로 바꿀 수 있어야 한다 (규약 변경 시 config 만 수정)."""
    publisher = _publisher(water_low_topic="potner/device/raspberry-01/custom/water")
    messages = publisher.build_messages({"water_present": False})

    assert messages[0][0] == "potner/device/raspberry-01/custom/water"


# --- water-low hold 게이트 (뜨개 잔떨림이 알림으로 새지 않게) -----------------


def _water_low_messages(messages):
    return [(t, p) for t, p in messages if t.endswith("/status/water-low")]


def test_water_low_blip_shorter_than_hold_sec_never_publishes():
    """hold_sec 미만 유지되다 원래 상태로 돌아오면 확정되지 않아 발행도 없다."""
    clock = iter([0.0, 3.0, 6.0])
    publisher = _publisher(water_low_hold_sec=10.0, time_fn=lambda: next(clock))

    r1 = publisher.build_messages({"water_present": False})  # t=0: 후보 등록
    r2 = publisher.build_messages({"water_present": True})  # t=3: 원상복귀 → 후보 폐기
    r3 = publisher.build_messages({"water_present": False})  # t=6: 새 후보(처음부터)

    assert _water_low_messages(r1) == []
    assert _water_low_messages(r2) == []
    assert _water_low_messages(r3) == []


def test_water_low_confirms_once_hold_sec_of_consistent_readings_elapses():
    clock = iter([0.0, 10.0])
    publisher = _publisher(water_low_hold_sec=10.0, time_fn=lambda: next(clock))

    r1 = publisher.build_messages({"water_present": False})  # t=0: 후보 등록, 아직 미확정
    assert _water_low_messages(r1) == []

    r2 = publisher.build_messages({"water_present": False})  # t=10: 10초 유지 → 확정
    (topic, payload) = _water_low_messages(r2)[0]
    assert topic.endswith("/status/water-low")
    assert payload["waterLow"] is True


def test_water_low_republishes_confirmed_value_every_call_once_confirmed():
    """확정 후에는 같은 확정값을 매 호출마다 그대로 재발행한다 (서버 처리 멱등)."""
    clock = iter([0.0, 10.0, 20.0, 30.0])
    publisher = _publisher(water_low_hold_sec=10.0, time_fn=lambda: next(clock))
    publisher.build_messages({"water_present": False})
    publisher.build_messages({"water_present": False})  # 확정

    for _ in range(2):
        (_, payload) = _water_low_messages(
            publisher.build_messages({"water_present": False})
        )[0]
        assert payload["waterLow"] is True


def test_water_low_sensor_failure_keeps_last_confirmed_value():
    """확정된 뒤 센서 읽기 실패(None)가 와도 마지막 확정값을 그대로 재발행한다."""
    clock = iter([0.0, 10.0, 20.0])
    publisher = _publisher(water_low_hold_sec=10.0, time_fn=lambda: next(clock))
    publisher.build_messages({"water_present": False})
    publisher.build_messages({"water_present": False})  # 확정: waterLow=True

    (_, payload) = _water_low_messages(publisher.build_messages({"water_present": None}))[0]
    assert payload["waterLow"] is True


def test_build_mqtt_publisher_reads_water_level_config(monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)
    publisher = build_mqtt_publisher(
        {
            "mqtt": {
                "enabled": False,
                "host": "broker.example",
                "device_id": "raspberry-01",
                "water_low_topic": "potner/device/raspberry-01/status/water-low",
            },
            "sensors": {"water_level": {"enabled": True, "report": False, "hold_sec": 3}},
        }
    )
    assert publisher.water_low_enabled is False
    assert publisher.water_low_topic == "potner/device/raspberry-01/status/water-low"
    assert publisher.water_low_hold_sec == 3.0


def test_build_mqtt_publisher_defaults_without_section(monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)
    publisher = build_mqtt_publisher(
        {"mqtt": {"enabled": False, "host": "broker.example", "device_id": "raspberry-01"}}
    )
    assert publisher.water_low_enabled is True
    # 토픽 미지정 시 서버 구독 패턴(potner/device/+/status/water-low)에 맞는 기본값
    assert publisher.water_low_topic == "potner/device/raspberry-01/status/water-low"
    assert publisher.water_low_hold_sec == 10.0


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
        assert section["report"] is True
        assert section["hold_sec"] == 10
        # 서버(WaterLowTopicParser)가 정확히 이 패턴을 구독한다
        assert cfg["mqtt"]["water_low_topic"] == "potner/device/raspberry-01/status/water-low"
