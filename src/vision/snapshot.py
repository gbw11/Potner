"""OV5647 (SunFounder Camera) snapshot helpers via picamera2."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

DEFAULT_CAPTURE_DIR = Path(__file__).resolve().parents[2] / "data" / "camera"


def capture_dir(path: Path | str | None = None) -> Path:
    out = Path(path) if path else DEFAULT_CAPTURE_DIR
    out.mkdir(parents=True, exist_ok=True)
    return out


def next_capture_path(directory: Path | str | None = None, prefix: str = "snap") -> Path:
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    base = capture_dir(directory)
    path = base / f"{prefix}_{stamp}.jpg"
    n = 1
    while path.exists():
        path = base / f"{prefix}_{stamp}_{n}.jpg"
        n += 1
    return path


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
