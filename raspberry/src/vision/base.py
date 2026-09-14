from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Optional, Protocol


@dataclass(frozen=True)
class CaptureResult:
    timestamp: str
    path: str
    width: int
    height: int
    driver: str
    ok: bool
    error: Optional[str] = None
    notes: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class Camera(Protocol):
    def capture(self, dest_path: str) -> CaptureResult: ...

    def close(self) -> None: ...
