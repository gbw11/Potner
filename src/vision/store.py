from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from .base import Camera, CaptureResult
from .naming import DEFAULT_EXT, next_available_path

logger = logging.getLogger(__name__)

# 촬영 이벤트 타입 (events.jsonl 안에서 다른 이벤트와 구분)
CAPTURE_EVENT_TYPE = "capture"


def capture_event(
    result: Optional[CaptureResult],
    *,
    trigger: str,
    request_id: Any = None,
    error: str | None = None,
    code: str | None = None,
    ok: Optional[bool] = None,
) -> dict[str, Any]:
    """촬영 1건을 EventStore 용 이벤트 dict 로 변환.

    index.jsonl 은 "찍힌 이미지 목록"이고, 이 이벤트는 "언제·누가 시켜서·성공했는지"의
    기록이다. 촬영 자체가 예외로 죽어 CaptureResult 가 없을 때도 남길 수 있도록
    ``result`` 는 None 을 허용한다.

    ``ok`` 를 넘기면 ``result.ok`` 대신 그 값을 쓴다 — 셔터는 정상이었지만 밝기 검사에
    탈락해 재촬영된 시도처럼, "촬영은 됐으나 쓸 수 없는" 경우를 실패로 남기기 위함이다.
    """
    if ok is None:
        ok = bool(result.ok) if result is not None else False
    timestamp = (
        result.timestamp
        if result is not None and result.timestamp
        else datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    )
    event: dict[str, Any] = {
        "timestamp": timestamp,
        "type": CAPTURE_EVENT_TYPE,
        "trigger": trigger,
        "ok": ok,
    }
    if request_id is not None:
        event["request_id"] = request_id

    if result is not None:
        name = Path(result.path).name if result.path else ""
        event["path"] = result.path
        event["file_name"] = name
        event["width"] = result.width
        event["height"] = result.height
        event["driver"] = result.driver
    else:
        name = ""

    reason = error or (result.error if result is not None else None)
    if not ok and reason:
        event["error"] = reason
    if code:
        event["code"] = code

    if ok:
        event["message"] = f"촬영 성공 ({trigger}): {name}"
    else:
        event["message"] = f"촬영 실패 ({trigger}): {reason or 'unknown error'}"
    return event


class CameraStore:
    """이미지 파일 + JSONL 인덱스로 카메라 정보 저장.

    ``event_store`` 를 주면 촬영 1건마다 촬영 이벤트도 함께 남긴다
    (index.jsonl = 이미지 목록, events.jsonl = 촬영 이력).
    """

    def __init__(
        self,
        directory: str | Path,
        index_name: str = "index.jsonl",
        *,
        event_store: Any = None,
        trigger: str = "collector",
    ) -> None:
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.index_path = self.directory / index_name
        if not self.index_path.exists():
            self.index_path.touch()
        self.event_store = event_store
        self.trigger = trigger

    def next_path(self, ext: str = DEFAULT_EXT) -> Path:
        """다음 촬영 파일 경로. 규칙은 naming.py 가 단독으로 갖는다."""
        return next_available_path(self.directory, ext=ext)

    def capture(
        self,
        camera: Camera,
        *,
        ext: str | None = None,
        trigger: str | None = None,
        request_id: Any = None,
    ) -> CaptureResult:
        # mock PNG / picamera jpg
        if ext is None:
            driver_hint = getattr(camera, "__class__", type(camera)).__name__.lower()
            ext = ".png" if "mock" in driver_hint else DEFAULT_EXT
        dest = self.next_path(ext=ext)
        source = trigger or self.trigger
        try:
            result = camera.capture(str(dest))
        except Exception as exc:  # noqa: BLE001 — 실패도 기록은 남기고 그대로 올린다
            logger.exception(f"촬영 예외 ({source}): {exc}")
            self.log_capture(None, trigger=source, request_id=request_id, error=str(exc))
            raise
        self._append_index(result)
        self.log_capture(result, trigger=source, request_id=request_id)
        return result

    def log_capture(
        self,
        result: Optional[CaptureResult],
        *,
        trigger: str | None = None,
        request_id: Any = None,
        error: str | None = None,
        code: str | None = None,
        ok: Optional[bool] = None,
    ) -> dict[str, Any]:
        """촬영 결과를 앱 로그 + 이벤트 로그에 남기고 이벤트 dict 를 돌려준다."""
        event = capture_event(
            result,
            trigger=trigger or self.trigger,
            request_id=request_id,
            error=error,
            code=code,
            ok=ok,
        )
        if event["ok"]:
            logger.info(f"{event['message']} ({event.get('width')}x{event.get('height')})")
        else:
            logger.warning(event["message"])
        self._append_event(event)
        return event

    def _append_event(self, event: dict[str, Any]) -> None:
        store = self.event_store
        if store is None:
            return
        try:
            store.append(event)
        except Exception as exc:  # noqa: BLE001 — 로그 실패로 촬영을 깨뜨리지 않는다
            logger.warning(f"촬영 이벤트 기록 실패: {exc}")

    def _append_index(self, result: CaptureResult) -> None:
        try:
            with self.index_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(result.to_dict(), ensure_ascii=False) + "\n")
        except OSError as exc:
            logger.warning(f"촬영 인덱스 기록 실패 ({self.index_path}): {exc}")

    def recent(self, limit: int = 20) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        with self.index_path.open(encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
        return rows[-limit:]

    def latest(self) -> Optional[dict[str, Any]]:
        items = self.recent(limit=1)
        return items[-1] if items else None
