"""촬영 이미지 파일명 규칙 — 단일 소스.

표준 형식:  ``frame_YYYYMMDD_HHMMSS[_n].{ext}``

- 시각은 **로컬 시간** (Pi 는 KST). 파일명을 눈으로 보고 촬영 시각을 바로 읽기 위함.
- 같은 초에 연속 촬영하면 ``_1``, ``_2`` … 접미사를 붙여 기존 파일을 절대 덮어쓰지 않는다.
- 확장자는 드라이버에 따라 다르다 (picamera2 → ``.jpg``, mock → ``.png``).

이전에는 ``store.py::CameraStore.next_path`` (frame_…) 와
``snapshot.py::next_capture_path`` (snap_…) 가 각각 규칙을 갖고 있어 서로 달랐다.
지금은 둘 다 이 모듈에 위임한다 — 규칙을 바꾸려면 여기만 고치면 된다.
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

# README·수집기·서버가 기대하는 실제 파일명 형식의 기준값
DEFAULT_PREFIX = "frame"
DEFAULT_EXT = ".jpg"
STAMP_FORMAT = "%Y%m%d_%H%M%S"


def normalize_ext(ext: str | None) -> str:
    """'jpg' / '.jpg' / None → '.jpg' 처럼 앞점 있는 확장자로 정규화."""
    if not ext:
        return DEFAULT_EXT
    return ext if ext.startswith(".") else f".{ext}"


def timestamp_stamp(now: Optional[datetime] = None) -> str:
    """파일명에 쓰는 로컬 시간 스탬프 (YYYYMMDD_HHMMSS)."""
    moment = now if now is not None else datetime.now(timezone.utc).astimezone()
    return moment.strftime(STAMP_FORMAT)


def build_filename(
    stamp: str,
    *,
    prefix: str = DEFAULT_PREFIX,
    ext: str = DEFAULT_EXT,
    seq: int = 0,
) -> str:
    """스탬프 + 순번으로 파일명 하나를 만든다 (seq=0 이면 접미사 없음)."""
    suffix = "" if seq <= 0 else f"_{seq}"
    return f"{prefix}_{stamp}{suffix}{normalize_ext(ext)}"


def next_available_path(
    directory: Path | str,
    *,
    prefix: str = DEFAULT_PREFIX,
    ext: str = DEFAULT_EXT,
    now: Optional[datetime] = None,
) -> Path:
    """``directory`` 안에서 아직 존재하지 않는 다음 촬영 파일 경로."""
    base = Path(directory)
    stamp = timestamp_stamp(now)
    seq = 0
    while True:
        path = base / build_filename(stamp, prefix=prefix, ext=ext, seq=seq)
        if not path.exists():
            return path
        seq += 1


def is_capture_filename(name: str | Path, *, prefix: str = DEFAULT_PREFIX) -> bool:
    """이 규칙으로 만들어진 파일명인지 (인덱스/업로드 쪽에서 필터링용)."""
    stem = Path(name).stem
    if not stem.startswith(f"{prefix}_"):
        return False
    parts = stem.split("_")
    # frame_YYYYMMDD_HHMMSS 또는 frame_YYYYMMDD_HHMMSS_n
    if len(parts) not in (3, 4):
        return False
    date_part, time_part = parts[1], parts[2]
    if not (len(date_part) == 8 and date_part.isdigit()):
        return False
    if not (len(time_part) == 6 and time_part.isdigit()):
        return False
    return len(parts) == 3 or parts[3].isdigit()
