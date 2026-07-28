from __future__ import annotations

from src.mqtt import heartbeat_payload, telemetry_payload
from src.transport.spring import SpringSoilPublisher, build_spring_publisher


def test_spring_soil_payload_shape():
    publisher = SpringSoilPublisher(
        base_url="http://example.com",
        device_id="pi-01",
        enabled=False,
    )
    payload = publisher.build_payload(
        {
            "timestamp": "2026-07-27T10:00:00+09:00",
            "soil_raw": 14200,
            "soil_moisture_pct": 48.3,
        }
    )

    assert payload == {
        "deviceId": "pi-01",
        "timestamp": "2026-07-27T10:00:00+09:00",
        "soilRaw": 14200,
        "soilMoisturePct": 48.3,
    }
    assert publisher.url == "http://example.com/api/v1/sensors/soil"


def test_spring_publish_disabled_is_noop():
    publisher = SpringSoilPublisher(base_url="http://example.com", enabled=False)
    result = publisher.publish({"soil_raw": 1, "soil_moisture_pct": 1.0})
    assert result.ok is True
    assert result.message == "disabled"


def test_build_spring_publisher_reads_config(monkeypatch):
    monkeypatch.delenv("SPRING_BASE_URL", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)
    publisher = build_spring_publisher(
        {
            "backend": {
                "enabled": False,
                "base_url": "http://127.0.0.1:8080",
                "device_id": "pi-01",
            }
        }
    )
    assert publisher.device_id == "pi-01"
    assert publisher.enabled is False


def test_mqtt_telemetry_and_heartbeat_keys():
    tele = telemetry_payload(
        device_id="raspberry-01",
        sensor_type="TEMPERATURE",
        value=23.46,
        unit="CELSIUS",
        measured_at="2026-07-27T01:00:00Z",
    )
    heart = heartbeat_payload(device_id="raspberry-01", sent_at="2026-07-27T01:00:00Z")

    assert tele["deviceId"] == "raspberry-01"
    assert tele["sensorType"] == "TEMPERATURE"
    assert tele["unit"] == "CELSIUS"
    assert tele["value"] == 23.5
    assert "messageId" in tele
    assert heart["deviceId"] == "raspberry-01"
    assert "sentAt" in heart
