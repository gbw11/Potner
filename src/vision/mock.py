from __future__ import annotations

import struct
import zlib
from datetime import datetime, timezone
from pathlib import Path

from .base import CaptureResult


def _utc_now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _write_minimal_png(path: Path, width: int = 320, height: int = 240) -> None:
    """Dependency-free tiny PNG (solid green) for Windows mock."""
    # RGBA green plant-ish color
    row = b"\x00" + (b"\x3c\xa8\x5c\xff" * width)
    raw = row * height
    compressed = zlib.compress(raw, 9)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", compressed) + chunk(b"IEND", b"")
    path.write_bytes(png)


class MockCamera:
    """PC에서 파이프라인 검증용. 실제 OV5647 대신 더미 PNG 저장."""

    def __init__(self, width: int = 1280, height: int = 720) -> None:
        self.width = width
        self.height = height

    def capture(self, dest_path: str) -> CaptureResult:
        path = Path(dest_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        # mock은 용량 줄이려고 작은 PNG
        _write_minimal_png(path, width=min(320, self.width), height=min(240, self.height))
        return CaptureResult(
            timestamp=_utc_now(),
            path=str(path),
            width=min(320, self.width),
            height=min(240, self.height),
            driver="mock",
            ok=True,
            notes="SunFounder OV5647 mock frame",
        )

    def close(self) -> None:
        return None
