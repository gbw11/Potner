"""서버 → Pi 촬영 명령 구독 리스너.

흐름:
  서버가 command_topic 으로 {"requestId": "..."} 발행
  → Pi 가 수신, 카메라로 촬영 후 이미지 저장 (collector 의 카메라 재사용)
  → result_topic 으로 성공/실패 결과 회신

명령 페이로드: {"requestId": <문자열|숫자>} — 필수. 서버측 매칭용으로 결과에 그대로 회신.
그 외 필드는 무시한다 (향후 촬영 옵션 확장 여지).

실패 시 결과에 status=ERROR 와 오류 코드(code)·메시지(error)를 담는다:
  INVALID_PAYLOAD  JSON 파싱 실패 / 객체가 아님
  INVALID_REQUEST  requestId 누락·형식 오류
  CAPTURE_FAILED   카메라가 촬영 실패를 보고 (미연결, 드라이버 오류 등)
  CAMERA_ERROR     촬영 함수 예외 (카메라 비활성 등)
  IMAGE_SAVE_FAILED 촬영은 됐다는데 파일이 없거나 0바이트
  IMAGE_TOO_DARK / IMAGE_TOO_BRIGHT / IMAGE_QUALITY_UNKNOWN
                   촬영은 됐지만 밝기 검사 탈락 (retryable=true → 서버가 재촬영 요청 가능)
촬영 중 새 명령은 status=BUSY 로 즉시 회신하고 무시한다.

촬영 성공 후 순서: 밝기 검사 → (통과 시) 업로드 → 결과 회신.
둘 다 설정이 꺼져 있으면 no-op 이고, 업로드 실패는 촬영 결과 회신을 막지 않는다.

촬영(+저장 확인 + 밝기 검사)은 `src/vision/capture_flow.py::capture_with_retry` 가
config `camera.retry:` 정책대로 재시도한다. 회신에는 실제 시도 횟수(attempts)가 붙는다.
재시도 대상이 아닌 실패(잘못된 requestId·페이로드, 카메라 비활성)는 즉시 실패로 회신한다.
재시도 동안 `_capture_lock` 을 계속 쥐고 있으므로 그 사이 들어온 명령은 BUSY 로 회신된다
(카메라는 한 대뿐이라 어차피 동시 촬영이 불가능하다 — 재시도 시간은 yaml 로 조절한다).
"""

from __future__ import annotations

import json
import logging
import os
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional

from ..device import resolve_device_id
from ..transport.uploader import ImageUploader, build_image_uploader
from ..vision.base import CaptureResult
from ..vision.capture_flow import AttemptOutcome, CaptureAttempt, RetryPolicy, capture_with_retry
from ..vision.quality import BrightnessThresholds
from ..vision.store import capture_event
from .client import _make_client

logger = logging.getLogger(__name__)

# 이 리스너가 남기는 촬영 이벤트의 트리거 주체 (collector 자동 촬영과 구분)
CAPTURE_TRIGGER = "mqtt"


def _utc_now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class CaptureCommandListener:
    """촬영 명령 MQTT 구독 + 촬영 + 결과 회신.

    촬영은 워커 스레드에서 수행해 MQTT 네트워크 루프를 막지 않는다.
    카메라는 collector 가 소유하므로 (Picamera2 는 이중 오픈 불가)
    촬영 함수(capture_fn)만 주입받는다.

    ``event_store`` 를 주면 원격 촬영 1건마다 (재시도분 포함) 촬영 이벤트(trigger="mqtt")를 남긴다.
    ``quality`` / ``uploader`` / ``retry`` 는 None 이면 각각 밝기 검사·업로드·재시도 없이 기존 동작.
    ``sleep_fn`` 은 재시도 대기 주입용 (테스트에서 실제로 기다리지 않게).
    """

    def __init__(
        self,
        *,
        capture_fn: Callable[[], CaptureResult],
        host: str,
        port: int = 1883,
        qos: int = 1,
        device_id: str = "unknown-device",
        username: str | None = None,
        password: str | None = None,
        command_topic: str,
        result_topic: str,
        enabled: bool = True,
        event_store: Any = None,
        quality: Optional[BrightnessThresholds] = None,
        uploader: Optional[ImageUploader] = None,
        retry: Optional[RetryPolicy] = None,
        sleep_fn: Optional[Callable[[float], None]] = None,
    ) -> None:
        self.enabled = enabled
        self.capture_fn = capture_fn
        self.host = host
        self.port = port
        self.qos = qos
        self.device_id = device_id
        self.username = username
        self.password = password or ""
        self.command_topic = command_topic
        self.result_topic = result_topic
        self.event_store = event_store
        self.quality = quality
        self.uploader = uploader
        self.retry = retry
        self.sleep_fn = sleep_fn
        self._client = None
        self._capture_lock = threading.Lock()

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
            client_id=f"{self.device_id}-capture",
        )
        client.on_message = self._on_message

        def _on_connect(c, _userdata, _flags, reason_code, _props=None) -> None:
            # 접속 실패에도 on_connect 가 불린다 — 그대로 subscribe 하면 조용히 실패한다
            if getattr(reason_code, "is_failure", False):
                logger.error(f"MQTT 접속 실패: {reason_code}")
                return
            # 재접속 시에도 구독 유지
            c.subscribe(self.command_topic, qos=self.qos)
            logger.info(f"구독 시작: {self.command_topic} (결과 회신 → {self.result_topic})")

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
        # 촬영은 AE 수렴 대기 포함 수 초 blocking → 워커 스레드에서 수행
        threading.Thread(target=self._handle_raw, args=(raw,), daemon=True).start()

    def _handle_raw(self, raw: str) -> None:
        self._publish_result(self.handle_raw_command(raw))

    def handle_raw_command(self, raw: str) -> dict[str, Any]:
        """원문 1건 파싱 + 처리 후 결과 페이로드 반환 (MQTT 없이 단위 테스트 가능)."""
        try:
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError("payload must be a JSON object")
        except (json.JSONDecodeError, ValueError) as exc:
            return self._error_result(None, "INVALID_PAYLOAD", f"invalid payload: {exc}")
        return self.handle_command(payload)

    def handle_command(self, payload: dict[str, Any]) -> dict[str, Any]:
        request_id = payload.get("requestId")
        # 페이로드 오류는 재시도 대상이 아니다 — 같은 명령을 다시 처리해도 결과가 같다
        if request_id is None or not isinstance(request_id, (str, int)) or request_id == "":
            return self._error_result(
                None, "INVALID_REQUEST", f"missing or invalid requestId: {payload}"
            )

        if not self._capture_lock.acquire(blocking=False):
            logger.warning(f"촬영 중 — 새 명령 무시 (requestId={request_id})")
            return {**self._base_result(request_id), "status": "BUSY"}
        try:
            logger.info(f"촬영 명령 수신 (requestId={request_id})")
            # 촬영 → 저장 확인 → 밝기 검사까지가 1회 시도. 재시도도 카메라를 쓰므로
            # 락을 쥔 채로 돈다 (그 사이 들어온 명령은 BUSY).
            attempt = capture_with_retry(
                self.capture_fn,
                policy=self.retry,
                quality=self.quality,
                sleep_fn=self.sleep_fn,
                request_id=request_id,
                on_attempt=lambda outcome: self._record_attempt(outcome, request_id),
            )
        finally:
            self._capture_lock.release()

        if not attempt.ok:
            return self._failure_result(request_id, attempt)

        result = attempt.result
        body = {
            **self._base_result(request_id),
            "status": "OK",
            "path": result.path,
            "fileName": Path(result.path).name,
            "width": result.width,
            "height": result.height,
            "driver": result.driver,
            "capturedAt": result.timestamp,
            "attempts": attempt.attempts,
        }
        if attempt.report is not None:
            body["quality"] = attempt.report.to_dict()
        # 업로드 실패는 촬영 결과 회신을 막지 않는다 (서버가 uploaded=false 로 판단)
        if self.uploader is not None:
            upload = self.uploader.upload_capture(result, request_id=request_id)
            if upload.code != "DISABLED":
                body["uploaded"] = upload.ok
                if not upload.ok:
                    body["uploadError"] = upload.message
        return body

    def _failure_result(self, request_id: Any, attempt: CaptureAttempt) -> dict[str, Any]:
        """재시도까지 모두 실패한 촬영 → 서버 회신 페이로드."""
        body = {
            **self._error_result(
                request_id,
                attempt.code or "CAPTURE_FAILED",
                attempt.error or "capture failed",
            ),
            "attempts": attempt.attempts,
        }
        report = attempt.report
        if report is not None:
            # 밝기 탈락: 이미지 자체는 남아 있으므로 경로와 판정 근거를 함께 회신한다.
            # retryable 은 "서버가 나중에 다시 요청할 가치가 있는가" — 기기 쪽 재시도는
            # 이미 attempts 회 소진했다는 뜻이다.
            if attempt.result is not None and attempt.result.path:
                body["path"] = attempt.result.path
            body["retryable"] = report.needs_recapture
            body["quality"] = report.to_dict()
        return body

    def _record_attempt(self, outcome: AttemptOutcome, request_id: Any) -> None:
        """시도 1회를 촬영 이력(events.jsonl)에 남긴다 — 재시도분도 각각 남는다.

        밝기 탈락처럼 셔터는 정상이었지만 쓸 수 없는 이미지도 **실패로** 남긴다.
        이력만 보고 "왜 재시도됐는지" 알 수 있어야 하기 때문에 사유(code/error)를 함께 싣는다.
        """
        self._log_capture(
            outcome.result,
            request_id,
            code=None if outcome.ok else outcome.code,
            error=None if outcome.ok else outcome.error,
            ok=outcome.ok,
        )

    def _base_result(self, request_id: Any) -> dict[str, Any]:
        base: dict[str, Any] = {
            "messageId": str(uuid.uuid4()),
            "deviceId": self.device_id,
            "measuredAt": _utc_now_z(),
        }
        if request_id is not None:
            base["requestId"] = request_id
        return base

    def _log_capture(
        self,
        result: Optional[CaptureResult],
        request_id: Any,
        *,
        code: str | None = None,
        error: str | None = None,
        ok: bool | None = None,
    ) -> dict[str, Any]:
        """촬영 1건을 앱 로그 + 이벤트 로그(events.jsonl)에 남긴다."""
        event = capture_event(
            result,
            trigger=CAPTURE_TRIGGER,
            request_id=request_id,
            error=error,
            code=code,
            ok=ok,
        )
        if event["ok"]:
            logger.info(f"{event['message']} → {event.get('path')}")
        else:
            logger.warning(event["message"])
        store = self.event_store
        if store is not None:
            try:
                store.append(event)
            except Exception as exc:  # noqa: BLE001 — 로그 실패로 회신을 막지 않는다
                logger.warning(f"촬영 이벤트 기록 실패: {exc}")
        return event

    def _error_result(self, request_id: Any, code: str, message: str) -> dict[str, Any]:
        logger.error(f"촬영 명령 오류 ({code}): {message}")
        return {
            **self._base_result(request_id),
            "status": "ERROR",
            "code": code,
            "error": message,
        }

    def _publish_result(self, result: dict[str, Any]) -> None:
        client = self._client
        if client is None:
            return
        try:
            info = client.publish(
                self.result_topic,
                json.dumps(result, ensure_ascii=False),
                qos=self.qos,
                retain=False,
            )
            info.wait_for_publish(timeout=5.0)
            if info.is_published():
                logger.info(f"결과 회신 완료 (status={result.get('status')})")
            else:
                logger.warning("결과 회신 시간 초과")
        except Exception as exc:  # noqa: BLE001 — 회신 실패해도 리스너 유지
            logger.error(f"결과 회신 실패: {exc}")


def _build_event_store(config: dict[str, Any]) -> Any:
    """config 에 events 섹션이 있을 때만 촬영 이벤트 로그를 붙인다 (없으면 앱 로그만)."""
    events_cfg = config.get("events") or {}
    path = events_cfg.get("path")
    if not path:
        return None
    try:
        from ..events.store import EventStore

        return EventStore(path)
    except OSError as exc:
        logger.warning(f"촬영 이벤트 로그를 열 수 없습니다 ({path}): {exc}")
        return None


def build_capture_listener(
    config: dict[str, Any],
    *,
    capture_fn: Callable[[], CaptureResult],
) -> Optional[CaptureCommandListener]:
    """mqtt + camera 가 모두 활성일 때만 리스너 생성."""
    mqtt_cfg = config.get("mqtt", {}) or {}
    if not mqtt_cfg.get("enabled", False):
        return None
    cam_cfg = config.get("camera", {}) or {}
    if not cam_cfg.get("enabled", False):
        return None

    device_id = resolve_device_id(mqtt_cfg.get("device_id"))
    username = mqtt_cfg.get("username") or os.environ.get("MQTT_USERNAME")
    password = os.environ.get("MQTT_PASSWORD") or mqtt_cfg.get("password") or ""
    # 토픽의 device 세그먼트는 브로커 ACL 계정(username) 기준 (water_command 와 동일 규칙)
    topic_device = str(username) if username else device_id
    command_topic = str(
        mqtt_cfg.get("capture_command_topic")
        or f"potner/device/{topic_device}/command/capture"
    )
    result_topic = str(
        # 서버 규약은 result/capture (55b35be). yaml 이 없을 때의 폴백도 규약과 맞춰 둔다
        mqtt_cfg.get("capture_result_topic")
        or f"potner/device/{topic_device}/result/capture"
    )

    return CaptureCommandListener(
        capture_fn=capture_fn,
        event_store=_build_event_store(config),
        host=str(mqtt_cfg.get("host", "127.0.0.1")),
        port=int(mqtt_cfg.get("port", 1883)),
        qos=int(mqtt_cfg.get("qos", 1)),
        device_id=device_id,
        username=str(username) if username else None,
        password=str(password) if password else None,
        command_topic=command_topic,
        result_topic=result_topic,
        enabled=True,
        quality=BrightnessThresholds.from_config(config),
        uploader=build_image_uploader(config),
        retry=RetryPolicy.from_config(config),
    )
