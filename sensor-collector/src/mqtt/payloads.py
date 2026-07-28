"""potner MQTT telemetry / heartbeat payload builders."""

from __future__ import annotations

import uuid
from typing import Any


def telemetry_payload(
    *,
    device_id: str,
    sensor_type: str,
    value: float,
    unit: str,
    measured_at: str,
) -> dict[str, Any]:
    return {
        "messageId": str(uuid.uuid4()),
        "deviceId": device_id,
        "sensorType": sensor_type,
        "value": round(value, 1),
        "unit": unit,
        "measuredAt": measured_at,
    }


def heartbeat_payload(*, device_id: str, sent_at: str) -> dict[str, Any]:
    return {
        "messageId": str(uuid.uuid4()),
        "deviceId": device_id,
        "sentAt": sent_at,
    }
