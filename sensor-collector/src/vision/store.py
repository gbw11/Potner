from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from .base import Camera, CaptureResult


class CameraStore:
    """이미지 파일 + JSONL 인덱스로 카메라 정보 저장."""

    def __init__(self, directory: str | Path, index_name: str = "index.jsonl") -> None:
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.index_path = self.directory / index_name
        if not self.index_path.exists():
            self.index_path.touch()

    def next_path(self, ext: str = ".jpg") -> Path:
        stamp = datetime.now(timezone.utc).astimezone().strftime("%Y%m%d_%H%M%S")
        return self.directory / f"frame_{stamp}{ext}"

    def capture(self, camera: Camera, *, ext: str | None = None) -> CaptureResult:
        # mock PNG / picamera jpg
        if ext is None:
            driver_hint = getattr(camera, "__class__", type(camera)).__name__.lower()
            ext = ".png" if "mock" in driver_hint else ".jpg"
        dest = self.next_path(ext=ext)
        result = camera.capture(str(dest))
        self._append_index(result)
        return result

    def _append_index(self, result: CaptureResult) -> None:
        with self.index_path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(result.to_dict(), ensure_ascii=False) + "\n")

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
