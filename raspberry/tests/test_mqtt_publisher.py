from __future__ import annotations

from src.mqtt.publisher import MqttTelemetryPublisher, build_mqtt_publisher


def test_build_messages_sensor_data_only_with_device_id():
    publisher = MqttTelemetryPublisher(
        host="127.0.0.1",
        device_id="fallback-id",
        enabled=False,
    )
    messages = publisher.build_messages(
        {
            "device_id": "serial-abc",
            "temperature_c": 23.4,
            "humidity_pct": 51.2,
            "light_lux": None,
            "soil_moisture_pct": None,
        }
    )

    assert len(messages) == 2
    types = [p["sensorType"] for _, p in messages]
    assert types == ["TEMPERATURE", "HUMIDITY"]
    assert all(p["deviceId"] == "serial-abc" for _, p in messages)
    assert "summary" not in messages[0][1]
    assert "message" not in messages[0][1] or "괜찮" not in str(messages[0][1])


def test_build_messages_ignores_light_and_soil_even_when_present():
    """조도·토양수분은 Pi MQTT 발행 대상이 아니다."""
    publisher = MqttTelemetryPublisher(
        host="127.0.0.1",
        device_id="serial-abc",
        enabled=False,
    )
    messages = publisher.build_messages(
        {
            "device_id": "serial-abc",
            "temperature_c": 20.0,
            "humidity_pct": 40.0,
            "light_lux": 300.0,
            "soil_moisture_pct": 55.0,
        }
    )
    types = [p["sensorType"] for _, p in messages]
    assert types == ["TEMPERATURE", "HUMIDITY"]


def test_build_messages_skips_missing_climate():
    publisher = MqttTelemetryPublisher(
        host="127.0.0.1",
        enabled=False,
    )
    messages = publisher.build_messages({"temperature_c": None, "humidity_pct": None})
    assert messages == []


def test_publish_disabled_is_noop():
    publisher = MqttTelemetryPublisher(host="127.0.0.1", enabled=False)
    result = publisher.publish({"temperature_c": 20.0, "humidity_pct": 40.0})
    assert result.ok is True
    assert result.message == "disabled"
    assert result.count == 0


def test_build_mqtt_publisher_from_config(monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)
    publisher = build_mqtt_publisher(
        {
            "mqtt": {
                "enabled": True,
                "host": "broker.example",
                "port": 1884,
                "device_id": "raspberry-01",
                "username": "raspberry-01",
                "password": "secret",
            }
        }
    )
    assert publisher.enabled is True
    assert publisher.host == "broker.example"
    assert publisher.port == 1884
    assert publisher.username == "raspberry-01"
    assert publisher.password == "secret"
    assert publisher.device_id == "raspberry-01"
    assert publisher.telemetry_topic == "potner/device/raspberry-01/sensor/telemetry"
    assert publisher.heartbeat_topic == "potner/device/raspberry-01/status/heartbeat"
    assert publisher.heartbeat_interval_sec == 30.0


class _FakeInfo:
    def wait_for_publish(self, timeout=None):
        pass

    def is_published(self):
        return True


class _FakeClient:
    def __init__(self):
        self.published: list[tuple[str, str]] = []

    def publish(self, topic, payload, qos=0, retain=False):
        self.published.append((topic, payload))
        return _FakeInfo()

    def loop_stop(self):
        pass

    def disconnect(self):
        pass


def test_heartbeat_publishes_on_interval():
    import json
    import time

    publisher = MqttTelemetryPublisher(
        host="127.0.0.1",
        device_id="raspberry-01",
        heartbeat_interval_sec=0.05,
        enabled=True,
    )
    fake = _FakeClient()
    publisher._client = fake  # 실제 브로커 없이 발행 경로만 검증
    publisher._start_heartbeat()
    time.sleep(0.2)
    publisher.close()

    assert len(fake.published) >= 2
    topic, raw = fake.published[0]
    assert topic == "potner/device/raspberry-01/status/heartbeat"
    payload = json.loads(raw)
    assert payload["deviceId"] == "raspberry-01"
    assert set(payload) == {"messageId", "deviceId", "sentAt"}
    ids = {json.loads(raw)["messageId"] for _, raw in fake.published}
    assert len(ids) == len(fake.published)


def test_heartbeat_stops_after_close():
    import time

    publisher = MqttTelemetryPublisher(
        host="127.0.0.1",
        device_id="raspberry-01",
        heartbeat_interval_sec=0.05,
        enabled=True,
    )
    fake = _FakeClient()
    publisher._client = fake
    publisher._start_heartbeat()
    time.sleep(0.1)
    publisher.close()
    count = len(fake.published)
    time.sleep(0.15)
    assert len(fake.published) == count
