"""Periodic potner MQTT publisher (sensor telemetry + heartbeat)."""

from __future__ import annotations

import json
import os
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Optional

import paho.mqtt.client as mqtt

from ..device import resolve_device_id
from .client import _make_client
from .payloads import heartbeat_payload, telemetry_payload, water_low_payload


@dataclass(frozen=True)
class MqttPublishResult:
    ok: bool
    message: str
    count: int = 0


def _utc_now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class WaterLowHoldFilter:
    """수위 신호가 hold_sec 이상 연속 유지되어야 서버 발행값을 바꾼다.

    뜨개가 경계에서 출렁이며 tick(수집 주기)마다 반대로 뒤집혀도, 확정값이
    hold_sec 동안 안 바뀌면 서버에는 이전 확정값이 그대로(멱등) 나간다 —
    순간 접촉만으로 물부족 알림이 튀는 것을 막는다. hold_sec<=0 이면 즉시 확정.
    """

    def __init__(self, hold_sec: float) -> None:
        self._hold_sec = hold_sec
        self._confirmed: Optional[bool] = None
        self._candidate: Optional[bool] = None
        self._candidate_since: Optional[float] = None

    def update(self, value: Optional[bool], now: float) -> Optional[bool]:
        if value is None:  # 센서 읽기 실패 — 마지막 확정값 유지
            return self._confirmed
        if value == self._confirmed:
            self._candidate = None
            self._candidate_since = None
            return self._confirmed
        if self._hold_sec <= 0:
            self._confirmed = value
            return self._confirmed
        if value != self._candidate:
            self._candidate = value
            self._candidate_since = now
            return self._confirmed
        assert self._candidate_since is not None
        if now - self._candidate_since >= self._hold_sec:
            self._confirmed = value
            self._candidate = None
            self._candidate_since = None
        return self._confirmed


class MqttTelemetryPublisher:
    """
    Collector reading → potner MQTT sensor messages + heartbeat.

    Sends available climate metrics (no status text):
      - TEMPERATURE / CELSIUS
      - HUMIDITY / PERCENT
      - 수위(water_present)는 텔레메트리가 아니라 **전용 상태 토픽**으로 보낸다:
        potner/device/{id}/status/water-low, 페이로드 waterLow 불리언
        (서버 WaterLowMessage 규약 — Server-develop d4f9219 에 머지됨).
        waterLow=false 도 매 주기 발행한다 — 서버 플래그 해제용, 처리는 멱등.
        발행값은 원시 읽기 그대로가 아니라 WaterLowHoldFilter 로 water_low_hold_sec
        이상 연속 유지된 값만 반영한다 — 뜨개 접촉 잔떨림이 알림으로 새지 않게 한다.

    조도(ILLUMINANCE)·토양수분(SOIL_MOISTURE)은 Pi 담당이 아니라 발행하지 않는다.

    heartbeat 는 connect() 이후 백그라운드 스레드가 주기 발행한다.
    서버는 90초간 heartbeat 가 없으면 OFFLINE 으로 표시한다.
    """

    def __init__(
        self,
        *,
        host: str,
        port: int = 1883,
        qos: int = 1,
        device_id: str = "unknown-device",
        username: str | None = None,
        password: str | None = None,
        telemetry_topic: str | None = None,
        heartbeat_topic: str | None = None,
        heartbeat_interval_sec: float = 30.0,
        enabled: bool = True,
        timeout: float = 5.0,
        water_low_enabled: bool = True,
        water_low_topic: str | None = None,
        water_low_hold_sec: float = 10.0,
        time_fn: Callable[[], float] | None = None,
    ) -> None:
        self.enabled = enabled
        self.host = host
        self.port = port
        self.qos = qos
        self.device_id = device_id
        self.username = username
        self.password = password or ""
        self.telemetry_topic = telemetry_topic or f"potner/device/{device_id}/sensor/telemetry"
        self.heartbeat_topic = heartbeat_topic or f"potner/device/{device_id}/status/heartbeat"
        self.heartbeat_interval_sec = heartbeat_interval_sec
        self.timeout = timeout
        # 수위 물부족 보고: sensors.water_level.report 가 발행 여부, 토픽은 mqtt.water_low_topic
        self.water_low_enabled = water_low_enabled
        self.water_low_topic = water_low_topic or f"potner/device/{device_id}/status/water-low"
        self.water_low_hold_sec = water_low_hold_sec
        self._time_fn = time_fn or time.monotonic
        self._water_low_hold = WaterLowHoldFilter(water_low_hold_sec)
        self._client: Optional[mqtt.Client] = None
        self._hb_thread: Optional[threading.Thread] = None
        self._hb_stop = threading.Event()

    def connect(self) -> None:
        if not self.enabled or self._client is not None:
            return
        if not self.username or not self.password:
            raise ValueError(
                "MQTT username/password 가 없습니다. "
                "mqtt.username / MQTT_PASSWORD 를 설정하세요."
            )
        client = _make_client(
            username=self.username,
            password=self.password,
            client_id=f"{self.device_id}-collector",
        )
        client.connect(self.host, self.port, keepalive=60)
        client.loop_start()
        self._client = client
        self._start_heartbeat()

    def close(self) -> None:
        self._stop_heartbeat()
        client = self._client
        self._client = None
        if client is None:
            return
        try:
            client.loop_stop()
        finally:
            client.disconnect()

    # --- heartbeat ---

    def _start_heartbeat(self) -> None:
        if self.heartbeat_interval_sec <= 0 or self._hb_thread is not None:
            return
        self._hb_stop = threading.Event()
        thread = threading.Thread(
            target=self._heartbeat_loop, name="mqtt-heartbeat", daemon=True
        )
        thread.start()
        self._hb_thread = thread

    def _stop_heartbeat(self) -> None:
        thread = self._hb_thread
        self._hb_thread = None
        if thread is None:
            return
        self._hb_stop.set()
        thread.join(timeout=2.0)

    def _heartbeat_loop(self) -> None:
        stop = self._hb_stop
        while not stop.is_set():
            self._publish_heartbeat()
            stop.wait(self.heartbeat_interval_sec)

    def _publish_heartbeat(self) -> None:
        client = self._client
        if client is None:
            return
        payload = heartbeat_payload(device_id=self.device_id, sent_at=_utc_now_z())
        try:
            client.publish(
                self.heartbeat_topic,
                json.dumps(payload, ensure_ascii=False),
                qos=self.qos,
                retain=False,
            )
        except Exception as exc:  # noqa: BLE001 — heartbeat 실패해도 수집은 계속
            print(f"[mqtt] heartbeat publish failed: {exc}")

    def build_messages(self, reading: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
        measured_at = _utc_now_z()
        device_id = str(reading.get("device_id") or self.device_id)
        messages: list[tuple[str, dict[str, Any]]] = []

        sensors = (
            ("temperature_c", "TEMPERATURE", "CELSIUS"),
            ("humidity_pct", "HUMIDITY", "PERCENT"),
        )
        for key, sensor_type, unit in sensors:
            value = reading.get(key)
            if value is None:
                continue
            messages.append(
                (
                    self.telemetry_topic,
                    telemetry_payload(
                        device_id=device_id,
                        sensor_type=sensor_type,
                        value=float(value),
                        unit=unit,
                        measured_at=measured_at,
                    ),
                )
            )

        # 수위(플로트 스위치): 전용 상태 토픽으로 waterLow 불리언 보고.
        # 원시값이 아니라 hold_sec 이상 연속 유지된 확정값만 발행 — 뜨개 잔떨림이
        # tick(수집 주기)마다 알림을 뒤집는 것을 막는다. 아직 확정된 적 없으면(None) 미발행.
        water_present = reading.get("water_present")
        if self.water_low_enabled:
            confirmed = self._water_low_hold.update(water_present, self._time_fn())
            if confirmed is not None:
                messages.append(
                    (
                        self.water_low_topic,
                        water_low_payload(
                            device_id=device_id,
                            water_low=not confirmed,
                            measured_at=measured_at,
                        ),
                    )
                )
        return messages

    def publish(self, reading: dict[str, Any]) -> MqttPublishResult:
        if not self.enabled:
            return MqttPublishResult(ok=True, message="disabled", count=0)

        messages = self.build_messages(reading)
        if not messages:
            return MqttPublishResult(ok=True, message="empty", count=0)

        try:
            self.connect()
            assert self._client is not None
            for topic, payload in messages:
                info = self._client.publish(
                    topic,
                    json.dumps(payload, ensure_ascii=False),
                    qos=self.qos,
                    retain=False,
                )
                info.wait_for_publish(timeout=self.timeout)
                if not info.is_published():
                    return MqttPublishResult(
                        ok=False,
                        message=f"timeout: {topic}",
                        count=0,
                    )
            return MqttPublishResult(ok=True, message="ok", count=len(messages))
        except Exception as exc:  # noqa: BLE001 — 수집 루프는 계속 돌아야 함
            return MqttPublishResult(ok=False, message=str(exc), count=0)


def build_mqtt_publisher(config: dict[str, Any]) -> MqttTelemetryPublisher:
    cfg = config.get("mqtt", {}) or {}
    water_cfg = (config.get("sensors", {}) or {}).get("water_level", {}) or {}
    device_id = resolve_device_id(cfg.get("device_id"))
    username = cfg.get("username") or os.environ.get("MQTT_USERNAME")
    password = os.environ.get("MQTT_PASSWORD") or cfg.get("password") or ""
    topic = cfg.get("telemetry_topic")
    if not topic:
        topic = f"potner/device/{device_id}/sensor/telemetry"
    hb_topic = cfg.get("heartbeat_topic")
    if not hb_topic:
        hb_topic = f"potner/device/{device_id}/status/heartbeat"
    return MqttTelemetryPublisher(
        host=str(cfg.get("host", "127.0.0.1")),
        port=int(cfg.get("port", 1883)),
        qos=int(cfg.get("qos", 1)),
        device_id=device_id,
        username=str(username) if username else None,
        password=str(password) if password else None,
        telemetry_topic=str(topic),
        heartbeat_topic=str(hb_topic),
        heartbeat_interval_sec=float(cfg.get("heartbeat_interval_sec", 30)),
        enabled=bool(cfg.get("enabled", False)),
        water_low_enabled=bool(water_cfg.get("report", True)),
        water_low_topic=str(cfg.get("water_low_topic")) if cfg.get("water_low_topic") else None,
        water_low_hold_sec=float(water_cfg.get("hold_sec", 10.0)),
    )
