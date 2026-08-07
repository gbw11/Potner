from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional, Sequence

from .base import CaptureResult


def _utc_now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# libcamera AwbModeEnum — picamera2 는 정수로도 받는다.
AWB_MODES: dict[str, int] = {
    "auto": 0,
    "incandescent": 1,
    "tungsten": 2,
    "fluorescent": 3,
    "indoor": 4,
    "daylight": 5,
    "cloudy": 6,
    "custom": 7,
}


def build_camera_controls(
    *,
    exposure_value: float = 1.0,
    brightness: float = 0.1,
    contrast: float = 1.1,
    saturation: float = 1.05,
    awb_mode: str = "auto",
    colour_gains: Optional[Sequence[float]] = None,
) -> dict[str, Any]:
    """config 값 → picamera2 컨트롤 dict. (하드웨어 없이 테스트 가능하도록 분리)

    `colour_gains` 가 주어지면 자동 화이트밸런스를 끄고 (red, blue) 게인을 고정한다.
    녹색빛이 끼는 장면에서 AWB 가 못 잡을 때 쓰는 수동 색보정 경로다.
    """
    controls: dict[str, Any] = {
        "AeEnable": True,
        # 양수 EV = 더 밝게 (실내/어두운 장면용)
        "ExposureValue": float(exposure_value),
        "Brightness": float(brightness),
        "Contrast": float(contrast),
        "Saturation": float(saturation),
    }

    if colour_gains:
        gains = [float(g) for g in colour_gains]
        if len(gains) != 2:
            raise ValueError(f"camera.colour_gains 는 [red, blue] 두 값이어야 합니다: {colour_gains!r}")
        if any(g <= 0 for g in gains):
            raise ValueError(f"camera.colour_gains 는 양수여야 합니다: {colour_gains!r}")
        controls["AwbEnable"] = False
        controls["ColourGains"] = (gains[0], gains[1])
        return controls

    controls["AwbEnable"] = True
    mode = str(awb_mode or "auto").strip().lower()
    if mode not in AWB_MODES:
        raise ValueError(
            f"알 수 없는 camera.awb_mode: {awb_mode!r} (가능: {', '.join(sorted(AWB_MODES))})"
        )
    if mode != "auto":
        controls["AwbMode"] = AWB_MODES[mode]
    return controls


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
        awb_mode: str = "auto",
        colour_gains: Optional[Sequence[float]] = None,
    ) -> None:
        # import 만 검증하고, 장치 오픈은 첫 촬영 때까지 미룬다.
        # (기동 시점에 /dev 카메라가 잠깐 안 보여도 mqtt/하트비트는 살아 있게)
        try:
            import picamera2  # noqa: F401
        except ImportError as exc:
            raise ImportError(
                "picamera2 가 필요합니다. Pi에서: sudo apt install -y python3-picamera2 "
                "그리고 venv를 --system-site-packages 로 만드세요."
            ) from exc

        self.width = width
        self.height = height
        self.camera_num = camera_num
        self.settle_sec = settle_sec
        self._controls: dict[str, Any] = build_camera_controls(
            exposure_value=exposure_value,
            brightness=brightness,
            contrast=contrast,
            saturation=saturation,
            awb_mode=awb_mode,
            colour_gains=colour_gains,
        )
        #: 마지막 촬영의 picamera2 metadata (ColourGains 등) — 색보정 계산에 쓴다
        self.last_metadata: dict[str, Any] = {}
        self._picam = None
        self._started = False

    def _ensure_started(self) -> None:
        if self._started:
            return
        from picamera2 import Picamera2

        if self._picam is None:
            self._picam = Picamera2(camera_num=self.camera_num)
            still = self._picam.create_still_configuration(
                main={"size": (self.width, self.height), "format": "RGB888"},
            )
            self._picam.configure(still)

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
            try:
                self.last_metadata = dict(self._picam.capture_metadata())
            except Exception:  # noqa: BLE001 — metadata 실패가 촬영을 망치면 안 된다
                self.last_metadata = {}
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
        if self._picam is None:
            return
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
        self._picam = None
