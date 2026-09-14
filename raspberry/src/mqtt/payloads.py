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


def water_low_payload(*, device_id: str, water_low: bool, measured_at: str) -> dict[str, Any]:
    """급수 스테이션 물 부족 보고 (서버 WaterLowMessage 규약).

    토픽: potner/device/{deviceId}/status/water-low
    서버 설계(Server-develop d4f9219): 수위 퍼센트가 아니라 불리언 — 판정은 센서를 아는
    장치가 한다. waterLow=false 도 보내야 서버 플래그가 내려가 다음 부족 때 알림이 다시
    나간다. 주기 보고와 상태 변화 보고 모두 허용 (서버 처리가 멱등). messageId 는 중복
    제거용이 아니라 장치 로그 대조용.
    """
    return {
        "messageId": str(uuid.uuid4()),
        "deviceId": device_id,
        "waterLow": bool(water_low),
        "measuredAt": measured_at,
    }


def heartbeat_payload(*, device_id: str, sent_at: str) -> dict[str, Any]:
    return {
        "messageId": str(uuid.uuid4()),
        "deviceId": device_id,
        "sentAt": sent_at,
    }
