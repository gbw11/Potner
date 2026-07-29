from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .base import CaptureResult


def _utc_now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


class Picamera2Camera:
    """Raspberry Pi CSI 카메라 (OV5647 / SunFounder Camera Rev1.3 등).

    보드에 필요:
      sudo apt install -y python3-picamera2
    또는 venv --system-site-packages 후 picamera2 사용.
    """

    def __init__(
        self,
        width: int = 1920,
        height: int = 1080,
        camera_num: int = 0,
        *,
        settle_sec: float = 1.0,
        exposure_value: float = 1.0,
        brightness: float = 0.1,
        contrast: float = 1.1,
        saturation: float = 1.05,
    ) -> None:
        try:
            from picamera2 import Picamera2
        except ImportError as exc:
            raise ImportError(
                "picamera2 가 필요합니다. Pi에서: sudo apt install -y python3-picamera2 "
                "그리고 venv를 --system-site-packages 로 만드세요."
            ) from exc

        self.width = width
        self.height = height
        self.settle_sec = settle_sec
        self._controls: dict[str, Any] = {
            "AeEnable": True,
            "AwbEnable": True,
            # 양수 EV = 더 밝게 (실내/어두운 장면용)
            "ExposureValue": float(exposure_value),
            "Brightness": float(brightness),
            "Contrast": float(contrast),
            "Saturation": float(saturation),
        }
        self._picam = Picamera2(camera_num=camera_num)
        still = self._picam.create_still_configuration(
            main={"size": (width, height), "format": "RGB888"},
        )
        self._picam.configure(still)
        self._started = False

    def _ensure_started(self) -> None:
        if not self._started:
            self._picam.start()
            # 컨트롤은 start 이후에 적용하는 편이 안정적
            try:
                self._picam.set_controls(self._controls)
            except Exception:  # noqa: BLE001
                # 일부 컨트롤이 보드/드라이버에서 미지원일 수 있음
                for key, value in self._controls.items():
                    try:
                        self._picam.set_controls({key: value})
                    except Exception:  # noqa: BLE001
                        pass
            self._started = True

    def capture(self, dest_path: str) -> CaptureResult:
        path = Path(dest_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            import time

            self._ensure_started()
            # AE/AWB 수렴 대기 (너무 짧으면 어두움)
            time.sleep(max(0.3, self.settle_sec))
            self._picam.capture_file(str(path))
            return CaptureResult(
                timestamp=_utc_now(),
                path=str(path),
                width=self.width,
                height=self.height,
                driver="picamera2",
                ok=True,
                notes="SunFounder Camera Rev1.3 (OV5647) FHD",
            )
        except Exception as exc:  # noqa: BLE001
            return CaptureResult(
                timestamp=_utc_now(),
                path=str(path),
                width=self.width,
                height=self.height,
                driver="picamera2",
                ok=False,
                error=str(exc),
            )

    def close(self) -> None:
        if self._started:
            try:
                self._picam.stop()
            except Exception:  # noqa: BLE001
                pass
            self._started = False
        try:
            self._picam.close()
        except Exception:  # noqa: BLE001
            pass
