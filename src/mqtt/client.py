"""MQTT (Mosquitto) client helpers."""

from __future__ import annotations

import json
import time
from typing import Any

import paho.mqtt.client as mqtt


def _make_client(
    *,
    username: str | None = None,
    password: str | None = None,
    client_id: str | None = None,
) -> mqtt.Client:
    # client_id 는 브로커 전체에서 유일해야 한다. 서버(potner-backend-prod)와
    # 겹치면 브로커가 기존 세션을 끊어 서로를 계속 밀어낸다.
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id or "")
    if username:
        client.username_pw_set(username, password or "")
    return client


def publish_json(
    *,
    host: str,
    port: int,
    topic: str,
    payload: dict[str, Any],
    username: str | None = None,
    password: str | None = None,
    qos: int = 1,
    retain: bool = False,
    timeout: float = 5.0,
) -> None:
    publish_many(
        host=host,
        port=port,
        messages=[(topic, payload)],
        username=username,
        password=password,
        qos=qos,
        retain=retain,
        timeout=timeout,
    )


def publish_many(
    *,
    host: str,
    port: int,
    messages: list[tuple[str, dict[str, Any]]],
    username: str | None = None,
    password: str | None = None,
    qos: int = 1,
    retain: bool = False,
    timeout: float = 5.0,
) -> None:
    """Publish multiple JSON messages on one MQTT connection."""
    client = _make_client(username=username, password=password)
    client.connect(host, port, keepalive=60)
    client.loop_start()
    try:
        for topic, payload in messages:
            info = client.publish(
                topic,
                json.dumps(payload, ensure_ascii=False),
                qos=qos,
                retain=retain,
            )
            info.wait_for_publish(timeout=timeout)
            if not info.is_published():
                raise TimeoutError(f"MQTT publish timeout: {topic}")
    finally:
        client.loop_stop()
        client.disconnect()


def subscribe_once(
    *,
    host: str,
    port: int,
    topic: str,
    username: str | None = None,
    password: str | None = None,
    timeout: float = 3.0,
) -> str | None:
    """Wait for one message on topic (for verify)."""
    received: list[str] = []

    def on_message(_client, _userdata, msg: mqtt.MQTTMessage) -> None:
        received.append(msg.payload.decode("utf-8", errors="replace"))

    client = _make_client(username=username, password=password)
    client.on_message = on_message
    client.connect(host, port, keepalive=30)
    client.subscribe(topic, qos=1)
    client.loop_start()
    try:
        deadline = time.time() + timeout
        while time.time() < deadline and not received:
            time.sleep(0.05)
    finally:
        client.loop_stop()
        client.disconnect()
    return received[0] if received else None
