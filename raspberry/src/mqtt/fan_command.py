"""서버 → Pi 팬(송풍) 명령 구독 리스너.

흐름:
  앱에서 송풍 버튼을 누르면 서버가 command_topic 으로 발행
  → Pi 가 수신, 정해진 풍량으로 정해진 시간만큼 팬을 돌린 뒤 자동 정지
  → result_topic 으로 결과 회신

**앱에는 송풍 버튼 하나만 있으므로 풍량·시간은 기기가 정한다.** 서버가 보내는
페이로드(`{"seconds": 30, "requestId": "..."}`)의 값은 쓰지 않는다 — `requestId` 만
결과에 되돌려준다(서버측 매칭용). 풍량·시간을 바꾸려면 코드가 아니라 config 를 고친다:

    fan:
      blow_speed_pct: 100   # 풍량 % (0~100)
      blow_run_sec: 10      # 가동 시간 (초)

결과 status:
  OK    가동 완료
  BUSY  이미 가동 중 (끝날 때까지 새 명령 무시 — 버튼 연타 방지)
  ERROR 팬 오류
"""

from __future__ import annotations

import json
import logging
import math
import os
import threading
import uuid
from datetime import datetime, timezone
from typing import Any, Optional

from ..actuators import build_fan
from ..actuators.base import Fan
from ..device import resolve_device_id
from .client import _make_client

log = logging.getLogger(__name__)

DEFAULT_BLOW_SPEED_PCT = 100.0
DEFAULT_BLOW_RUN_SEC = 10.0


def _utc_now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _positive_float(value: Any, fallback: float, label: str) -> float:
    """config 값을 유한한 양수 float 로. 못 쓸 값이면 fallback."""
    if value is None:
        return fallback
    try:
        num = float(value)
    except (TypeError, ValueError):
        num = float("nan")
    if not math.isfinite(num) or num <= 0:
        log.warning(f"{label} 가 0보다 큰 숫자가 아니라 {fallback:g} 을 씁니다: {value!r}")
        return fallback
    return num


class FanCommandListener:
    """팬 명령 MQTT 구독 + 고정 풍량/시간 가동 + 결과 회신.

    가동은 blow_run_sec 동안 blocking 이라 워커 스레드에서 수행해 MQTT 네트워크
    루프를 막지 않는다. 가동 중 새 명령이 오면 BUSY 로 회신하고 무시한다.
    """

    def __init__(
        self,
        *,
        fan: Fan,
        host: str,
        port: int = 1883,
        qos: int = 1,
        device_id: str = "unknown-device",
        username: str | None = None,
        password: str | None = None,
        command_topic: str,
        result_topic: str,
        enabled: bool = True,
        blow_speed_pct: float = DEFAULT_BLOW_SPEED_PCT,
        blow_run_sec: float = DEFAULT_BLOW_RUN_SEC,
    ) -> None:
        self.enabled = enabled
        self.fan = fan
        self.host = host
        self.port = port
        self.qos = qos
        self.device_id = device_id
        self.username = username
        self.password = password or ""
        self.command_topic = command_topic
        self.result_topic = result_topic
        # 명령이 오면 항상 이 풍량으로 이 시간만큼 — 서버 페이로드 값은 쓰지 않는다
        self.blow_speed_pct = min(blow_speed_pct, 100.0)
        self.blow_run_sec = blow_run_sec
        self._client = None
        self._run_lock = threading.Lock()

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
            client_id=f"{self.device_id}-fan",
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
        # 가동은 blow_run_sec 동안 blocking → 워커 스레드에서 수행
        threading.Thread(target=self._handle_raw, args=(raw,), daemon=True).start()

    def _handle_raw(self, raw: str) -> None:
        # 페이로드의 값은 쓰지 않으므로 파싱 실패도 거부 사유가 아니다 — 버튼을
        # 눌렀다는 사실만 있으면 된다. requestId 를 못 읽는 것이 유일한 손해.
        payload: dict[str, Any] = {}
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                payload = parsed
            else:
                log.warning(f"팬 명령 페이로드가 JSON 객체가 아닙니다 (그대로 가동): {raw!r}")
        except json.JSONDecodeError:
            log.warning(f"팬 명령 페이로드를 읽을 수 없습니다 (그대로 가동): {raw!r}")
        self._publish_result(self.handle_command(payload))

    def handle_command(self, payload: dict[str, Any]) -> dict[str, Any]:
        """명령 1건 처리 후 결과 페이로드 반환 (MQTT 없이 단위 테스트 가능)."""
        request_id = payload.get("requestId")

        if not self._run_lock.acquire(blocking=False):
            log.warning("팬 가동 중 새 명령 수신, BUSY 회신")
            return {**self._base_result(request_id), "status": "BUSY"}
        try:
            log.info(
                f"팬 명령 수신 → {self.blow_speed_pct:.0f}% / {self.blow_run_sec:g}s 가동"
            )
            self.fan.run_for(self.blow_run_sec, speed_pct=self.blow_speed_pct)
            log.info(f"팬 가동 완료: {self.blow_speed_pct:.0f}% / {self.blow_run_sec:g}s")
            return {
                **self._base_result(request_id),
                "status": "OK",
                "appliedSpeedPct": self.blow_speed_pct,
                "durationSec": self.blow_run_sec,
            }
        except Exception as exc:  # noqa: BLE001 — 팬 오류도 서버에 회신
            return self._error_result(request_id, str(exc))
        finally:
            self._run_lock.release()

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
        log.error(f"팬 명령 오류: {message}")
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
            log.warning(f"팬 결과 회신 실패: {exc}")


def build_fan_listener(config: dict[str, Any]) -> Optional[FanCommandListener]:
    """mqtt + fan 이 모두 활성일 때만 리스너 생성."""
    mqtt_cfg = config.get("mqtt", {}) or {}
    if not mqtt_cfg.get("enabled", False):
        return None

    fan = build_fan(config)
    if fan is None:
        return None

    fan_cfg = config.get("fan", {}) or {}
    device_id = resolve_device_id(mqtt_cfg.get("device_id"))
    username = mqtt_cfg.get("username") or os.environ.get("MQTT_USERNAME")
    password = os.environ.get("MQTT_PASSWORD") or mqtt_cfg.get("password") or ""
    # 토픽의 device 세그먼트는 브로커 ACL 계정(username) 기준 (telemetry_topic 과 동일한 규칙)
    topic_device = str(username) if username else device_id
    command_topic = str(
        mqtt_cfg.get("fan_command_topic") or f"potner/device/{topic_device}/command/fan"
    )
    result_topic = str(
        mqtt_cfg.get("fan_result_topic") or f"potner/device/{topic_device}/result/fan"
    )

    return FanCommandListener(
        fan=fan,
        host=str(mqtt_cfg.get("host", "127.0.0.1")),
        port=int(mqtt_cfg.get("port", 1883)),
        qos=int(mqtt_cfg.get("qos", 1)),
        device_id=device_id,
        username=str(username) if username else None,
        password=str(password) if password else None,
        command_topic=command_topic,
        result_topic=result_topic,
        enabled=True,
        blow_speed_pct=_positive_float(
            fan_cfg.get("blow_speed_pct"), DEFAULT_BLOW_SPEED_PCT, "fan.blow_speed_pct"
        ),
        blow_run_sec=_positive_float(
            fan_cfg.get("blow_run_sec"), DEFAULT_BLOW_RUN_SEC, "fan.blow_run_sec"
        ),
    )
