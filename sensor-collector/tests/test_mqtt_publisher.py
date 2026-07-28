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


def test_build_messages_includes_light_and_soil_when_present():
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
    assert types == ["TEMPERATURE", "HUMIDITY", "ILLUMINANCE", "SOIL_MOISTURE"]


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
