"""서버 → Pi 급수 명령 구독 리스너.

흐름:
  서버가 command_topic 으로 {"ml": 150} 발행
  → Pi 가 수신, 펌프를 보정값 기반 시간 제어로 가동
  → result_topic 으로 실제 급수 결과 회신

명령 페이로드: {"ml": <양>} — "amountMl" / "amount_ml" 키도 허용.
requestId 가 있으면 결과에 그대로 되돌려준다 (서버측 매칭용).
"""

from __future__ import annotations

import json
import os
import threading
import uuid
from datetime import datetime, timezone
from typing import Any, Optional

from ..actuators import build_pump
from ..actuators.base import WaterPump
from ..device import resolve_device_id
from .client import _make_client


def _utc_now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _extract_ml(payload: dict[str, Any]) -> Optional[float]:
    for key in ("ml", "amountMl", "amount_ml", "amount"):
        if key in payload:
            try:
                return float(payload[key])
            except (TypeError, ValueError):
                return None
    return None


class WaterCommandListener:
    """급수 명령 MQTT 구독 + 펌프 가동 + 결과 회신.

    가동은 워커 스레드에서 수행해 MQTT 네트워크 루프를 막지 않는다.
    급수 중 새 명령이 오면 즉시 BUSY 로 회신하고 무시한다 (중복 급수 방지).
    """

    def __init__(
        self,
        *,
        pump: WaterPump,
        host: str,
        port: int = 1883,
        qos: int = 1,
        device_id: str = "unknown-device",
        username: str | None = None,
        password: str | None = None,
        command_topic: str,
        result_topic: str,
        enabled: bool = True,
    ) -> None:
        self.enabled = enabled
        self.pump = pump
        self.host = host
        self.port = port
        self.qos = qos
        self.device_id = device_id
        self.username = username
        self.password = password or ""
        self.command_topic = command_topic
        self.result_topic = result_topic
        self._client = None
        self._dispense_lock = threading.Lock()

    def start(self) -> None:
        if not self.enabled or self._client is not None:
            return
        if not self.username or not self.password:
            raise ValueError(
                "MQTT username/password 가 없습니다. "
                "mqtt.username / MQTT_PASSWORD 를 설정하세요."
            )
        client = _make_client(username=self.username, password=self.password)
        client.on_message = self._on_message

        def _on_connect(c, _userdata, _flags, reason_code, _props=None) -> None:
            # 재접속 시에도 구독 유지
            c.subscribe(self.command_topic, qos=self.qos)

        client.on_connect = _on_connect
        client.connect(self.host, self.port, keepalive=60)
        client.loop_start()
        self._client = client

    def close(self) -> None:
        client = self._client
        self._client = None
        if client is None:
            return
        try:
            client.loop_stop()
        finally:
            client.disconnect()

    # --- 메시지 처리 ---

    def _on_message(self, _client, _userdata, msg) -> None:
        raw = msg.payload.decode("utf-8", errors="replace")
        # 급수는 최대 max_run_sec 동안 blocking → 워커 스레드에서 수행
        threading.Thread(target=self._handle_raw, args=(raw,), daemon=True).start()

    def _handle_raw(self, raw: str) -> None:
        try:
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError("payload must be a JSON object")
        except (json.JSONDecodeError, ValueError) as exc:
            self._publish_result(self._error_result(None, f"invalid payload: {exc}"))
            return
        result = self.handle_command(payload)
        self._publish_result(result)

    def handle_command(self, payload: dict[str, Any]) -> dict[str, Any]:
        """명령 1건 처리 후 결과 페이로드 반환 (MQTT 없이 단위 테스트 가능)."""
        request_id = payload.get("requestId")
        ml = _extract_ml(payload)
        if ml is None or ml <= 0:
            return self._error_result(request_id, f"invalid ml: {payload}")

        if not self._dispense_lock.acquire(blocking=False):
            return {
                **self._base_result(request_id),
                "status": "BUSY",
                "requestedMl": ml,
            }
        try:
            print(f"[water] 급수 명령 수신: {ml:.0f} ml")
            result = self.pump.dispense_ml(ml)
        except Exception as exc:  # noqa: BLE001 — 펌프 오류도 서버에 회신
            return self._error_result(request_id, str(exc))
        finally:
            self._dispense_lock.release()

        print(
            f"[water] 급수 완료: {result.dispensed_ml:.0f} ml / {result.duration_sec:.1f}s"
            + (" (capped)" if result.capped else "")
        )
        return {
            **self._base_result(request_id),
            "status": "OK",
            "requestedMl": result.requested_ml,
            "dispensedMl": result.dispensed_ml,
            "durationSec": result.duration_sec,
            "capped": result.capped,
        }

    def _base_result(self, request_id: Any) -> dict[str, Any]:
        base: dict[str, Any] = {
            "messageId": str(uuid.uuid4()),
            "deviceId": self.device_id,
            "measuredAt": _utc_now_z(),
        }
        if request_id is not None:
            base["requestId"] = request_id
        return base

    def _error_result(self, request_id: Any, message: str) -> dict[str, Any]:
        print(f"[water] 오류: {message}")
        return {**self._base_result(request_id), "status": "ERROR", "error": message}

    def _publish_result(self, result: dict[str, Any]) -> None:
        client = self._client
        if client is None:
            return
        try:
            client.publish(
                self.result_topic,
                json.dumps(result, ensure_ascii=False),
                qos=self.qos,
                retain=False,
            )
        except Exception as exc:  # noqa: BLE001 — 회신 실패해도 리스너 유지
            print(f"[water] 결과 회신 실패: {exc}")


def build_water_listener(config: dict[str, Any]) -> Optional[WaterCommandListener]:
    """mqtt + pump 가 모두 활성일 때만 리스너 생성."""
    mqtt_cfg = config.get("mqtt", {}) or {}
    if not mqtt_cfg.get("enabled", False):
        return None

    pump = build_pump(config)
    if pump is None:
        return None

    device_id = resolve_device_id(mqtt_cfg.get("device_id"))
    username = mqtt_cfg.get("username") or os.environ.get("MQTT_USERNAME")
    password = os.environ.get("MQTT_PASSWORD") or mqtt_cfg.get("password") or ""
    # 토픽의 device 세그먼트는 브로커 ACL 계정(username) 기준 (telemetry_topic 과 동일한 규칙)
    topic_device = str(username) if username else device_id
    command_topic = str(
        mqtt_cfg.get("water_command_topic") or f"potner/device/{topic_device}/command/water"
    )
    result_topic = str(
        mqtt_cfg.get("water_result_topic") or f"potner/device/{topic_device}/command/water/result"
    )

    return WaterCommandListener(
        pump=pump,
        host=str(mqtt_cfg.get("host", "127.0.0.1")),
        port=int(mqtt_cfg.get("port", 1883)),
        qos=int(mqtt_cfg.get("qos", 1)),
        device_id=device_id,
        username=str(username) if username else None,
        password=str(password) if password else None,
        command_topic=command_topic,
        result_topic=result_topic,
        enabled=True,
    )
