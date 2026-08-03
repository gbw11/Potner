"""서버 → Pi 급수 명령 구독 리스너.

흐름:
  서버가 command_topic 으로 {"ml": 150} 발행
  → Pi 가 수신, 펌프를 보정값 기반 시간 제어로 가동
  → result_topic 으로 실제 급수 결과 회신

명령 페이로드: {"ml": <양>} — "amountMl" / "amount_ml" 키도 허용.
requestId 가 있으면 결과에 그대로 되돌려준다 (서버측 매칭용).

결과 status:
  OK      급수함 (과급수 가드가 양을 줄였으면 limited=true 와 limitReason 이 함께 온다)
  SKIPPED 과급수 가드가 막아 급수하지 않음 (dispensedMl=0). skipCode 로 사유 구분
  BUSY    이미 급수 중
  ERROR   페이로드 오류 / 펌프 오류
"""

from __future__ import annotations

import json
import logging
import math
import os
import threading
import uuid
from datetime import datetime, timezone
from typing import Any, Callable, Optional

from ..actuators import build_pump
from ..actuators.base import WaterPump
from ..actuators.safety import WateringGuard, build_guard
from ..device import resolve_device_id
from .client import _make_client

log = logging.getLogger(__name__)


def _utc_now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _extract_ml(payload: dict[str, Any]) -> Optional[float]:
    """급수량 키를 찾아 유한한 float 로. NaN/Infinity 는 None 취급.

    json.loads 는 NaN/Infinity 리터럴을 그대로 float 로 파싱하므로
    (float('nan') <= 0 은 False 라 크기 검사도 통과한다) 여기서 걸러야 한다.
    """
    for key in ("ml", "amountMl", "amount_ml", "amount"):
        if key in payload:
            value = payload[key]
            if isinstance(value, bool):  # True 가 1.0 으로 급수되는 사고 방지
                return None
            try:
                num = float(value)
            except (TypeError, ValueError):
                return None
            return num if math.isfinite(num) else None
    return None


class WaterCommandListener:
    """급수 명령 MQTT 구독 + 펌프 가동 + 결과 회신.

    가동은 워커 스레드에서 수행해 MQTT 네트워크 루프를 막지 않는다.
    급수 중 새 명령이 오면 즉시 BUSY 로 회신하고 무시한다 (중복 급수 방지).
    guard 가 있으면 급수 전에 과급수 게이트를 통과해야 한다.
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
        guard: Optional[WateringGuard] = None,
    ) -> None:
        self.enabled = enabled
        self.pump = pump
        self.guard = guard
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
        client = _make_client(
            username=self.username,
            password=self.password,
            client_id=f"{self.device_id}-water",
        )
        client.on_message = self._on_message

        def _on_connect(c, _userdata, _flags, reason_code, _props=None) -> None:
            # 접속 실패에도 on_connect 가 불린다 — 그대로 subscribe 하면 조용히 실패한다
            if getattr(reason_code, "is_failure", False):
                log.error(f"MQTT 접속 실패: {reason_code}")
                return
            # 재접속 시에도 구독 유지
            c.subscribe(self.command_topic, qos=self.qos)
            log.info(f"구독 시작: {self.command_topic} (결과 회신 → {self.result_topic})")

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
            log.warning(f"급수 중 새 명령 수신, BUSY 회신: {ml:.0f} ml")
            return {
                **self._base_result(request_id),
                "status": "BUSY",
                "requestedMl": ml,
            }
        try:
            log.info(f"급수 명령 수신: {ml:.0f} ml")
            decision = None
            dispense_ml = ml
            if self.guard is not None:
                decision = self.guard.check(ml)
                if not decision.allowed:
                    log.warning(f"과급수 방지로 급수 생략 ({decision.code}): {decision.reason}")
                    return {
                        **self._base_result(request_id),
                        "status": "SKIPPED",
                        "requestedMl": ml,
                        "dispensedMl": 0.0,
                        "durationSec": 0.0,
                        "skipCode": decision.code,
                        "reason": decision.reason,
                    }
                if decision.limited:
                    log.warning(f"과급수 방지로 급수량 감액: {decision.reason}")
                dispense_ml = decision.ml

            result = self.pump.dispense_ml(dispense_ml)
            if self.guard is not None:
                self.guard.record(result.dispensed_ml)
        except Exception as exc:  # noqa: BLE001 — 펌프 오류도 서버에 회신
            return self._error_result(request_id, str(exc))
        finally:
            self._dispense_lock.release()

        log.info(
            f"급수 완료: {result.dispensed_ml:.0f} ml / {result.duration_sec:.1f}s"
            + (" (capped)" if result.capped else "")
        )
        response = {
            **self._base_result(request_id),
            "status": "OK",
            # 서버가 보낸 원래 요청량. 가드가 줄였으면 dispensedMl 과 다르다
            "requestedMl": ml,
            "dispensedMl": result.dispensed_ml,
            "durationSec": result.duration_sec,
            "capped": result.capped,
        }
        if result.floored:
            # 최소 가동 시간 때문에 요청보다 많이 나갔다는 뜻 (dispensedMl > requestedMl)
            response["floored"] = True
        if decision is not None and decision.limited:
            response["limited"] = True
            response["limitReason"] = decision.reason
        return response

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
        log.error(f"급수 명령 오류: {message}")
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
            log.warning(f"급수 결과 회신 실패: {exc}")


def build_water_listener(
    config: dict[str, Any],
    *,
    soil_provider: Optional[Callable[[], Optional[float]]] = None,
) -> Optional[WaterCommandListener]:
    """mqtt + pump 가 모두 활성일 때만 리스너 생성.

    soil_provider 는 토양수분(%)을 돌려주는 콜러블 — 과급수 게이트의 "이미 젖었으면
    스킵" 판정에만 쓴다. 넘기지 않으면 그 축만 비활성이고 나머지 게이트는 그대로 동작한다.
    센서를 여기서 직접 열지 않는 이유: collector 가 이미 같은 I2C 센서를 잡고 있어
    이중 오픈이 되기 때문. 호출부(main.py)가 collector 의 마지막 측정값을 넘겨주면 된다.
    """
    mqtt_cfg = config.get("mqtt", {}) or {}
    if not mqtt_cfg.get("enabled", False):
        return None

    pump = build_pump(config)
    if pump is None:
        return None

    guard = build_guard(config, soil_provider=soil_provider)

    device_id = resolve_device_id(mqtt_cfg.get("device_id"))
    username = mqtt_cfg.get("username") or os.environ.get("MQTT_USERNAME")
    password = os.environ.get("MQTT_PASSWORD") or mqtt_cfg.get("password") or ""
    # 토픽의 device 세그먼트는 브로커 ACL 계정(username) 기준 (telemetry_topic 과 동일한 규칙)
    topic_device = str(username) if username else device_id
    command_topic = str(
        mqtt_cfg.get("water_command_topic") or f"potner/device/{topic_device}/command/water"
    )
    result_topic = str(
        # 서버 규약은 result/water (55b35be). yaml 이 없을 때의 폴백도 규약과 맞춰 둔다
        mqtt_cfg.get("water_result_topic")
        or f"potner/device/{topic_device}/result/water"
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
        guard=guard,
    )
