"""OV5647 (SunFounder Camera) snapshot helpers via picamera2."""

from __future__ import annotations

import logging
from pathlib import Path

from .naming import DEFAULT_EXT, DEFAULT_PREFIX, next_available_path

logger = logging.getLogger(__name__)

DEFAULT_CAPTURE_DIR = Path(__file__).resolve().parents[2] / "data" / "camera"


def capture_dir(path: Path | str | None = None) -> Path:
    out = Path(path) if path else DEFAULT_CAPTURE_DIR
    out.mkdir(parents=True, exist_ok=True)
    return out


def next_capture_path(
    directory: Path | str | None = None,
    prefix: str = DEFAULT_PREFIX,
) -> Path:
    """CameraStore.next_path 와 동일한 규칙 (frame_YYYYMMDD_HHMMSS[_n].jpg).

    예전에는 여기만 ``snap_`` 접두사를 써서 저장 경로에 두 형식이 섞였다.
    규칙 본체는 naming.py 하나뿐이고, 이 함수는 picamera2 전용 진입점일 뿐이다.
    """
    return next_available_path(capture_dir(directory), prefix=prefix, ext=DEFAULT_EXT)


def capture_still(
    output: Path | str | None = None,
    *,
    directory: Path | str | None = None,
    size: tuple[int, int] = (2592, 1944),
    settle_sec: float = 1.0,
) -> Path:
    """Take one still photo and save under the project captures folder."""
    import time

    from picamera2 import Picamera2

    out = Path(output) if output else next_capture_path(directory)
    out.parent.mkdir(parents=True, exist_ok=True)
    logger.info(f"스냅샷 촬영 시작: {out.name}")

    picam2 = Picamera2()
    try:
        config = picam2.create_still_configuration(main={"size": size})
        picam2.configure(config)
        picam2.start()
        time.sleep(settle_sec)
        picam2.capture_file(str(out))
    finally:
        picam2.stop()
        picam2.close()

    return out
