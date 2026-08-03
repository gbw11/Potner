from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Optional

from .sensors.factory import SensorBundle, build_sensors
from .storage import CsvStorage
from .vision import build_camera, build_camera_store
from .vision.base import Camera, CaptureResult
from .vision.capture_flow import CameraUnavailableError
from .vision.store import CameraStore


class Collector:
    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.sensors: SensorBundle = build_sensors(config)
        storage_cfg = config.get("storage", {})
        self.storage = CsvStorage(storage_cfg.get("path", "data/readings.csv"))
        self.camera: Optional[Camera] = build_camera(config)
        self.camera_store: Optional[CameraStore] = (
            build_camera_store(config) if self.camera is not None else None
        )
        cam_cfg = config.get("camera", {}) or {}
        self._capture_with_read = bool(cam_cfg.get("capture_with_read", False))
        self._read_count = 0
        self._capture_every_n = int(cam_cfg.get("capture_every_n", 1))

    def read_once(self, *, capture: bool | None = None) -> dict[str, Any]:
        climate = self._safe_read(self.sensors.climate)
        light = self._safe_read(self.sensors.light)
        soil = self._safe_read(self.sensors.soil)
        water_level = self._safe_read_water_level(self.sensors.water_level)

        row: dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
            "temperature_c": None if climate is None else climate.temperature_c,
            "humidity_pct": None if climate is None else climate.humidity_pct,
            "light_lux": None if light is None else light.lux,
            "soil_raw": None if soil is None else soil.raw,
            "soil_moisture_pct": None if soil is None else soil.moisture_pct,
            "water_present": None if water_level is None else water_level.water_present,
            "camera_path": None,
            "camera_ok": None,
        }

        do_capture = self._should_capture(capture)
        if do_capture and self.camera is not None and self.camera_store is not None:
            result = self.camera_store.capture(self.camera)
            row["camera_path"] = result.path if result.ok else None
            row["camera_ok"] = result.ok

        self.storage.write(row)
        return row

    def capture_once(self) -> CaptureResult:
        if self.camera is None or self.camera_store is None:
            # 설정 문제라 재촬영해도 결과가 같다 → 재시도 계층이 즉시 포기하도록
            # RuntimeError 하위 타입으로 표시한다 (기존 except RuntimeError 는 그대로 동작).
            raise CameraUnavailableError(
                "Camera disabled. Set camera.enabled: true in config "
                "(mock on PC, picamera2 on Raspberry Pi)."
            )
        return self.camera_store.capture(self.camera)

    def _should_capture(self, capture: bool | None) -> bool:
        if capture is not None:
            return capture
        if not self._capture_with_read:
            return False
        self._read_count += 1
        return self._read_count % max(1, self._capture_every_n) == 0

    @staticmethod
    def _safe_read(sensor: Any) -> Any:
        if sensor is None:
            return None
        try:
            return sensor.read()
        except Exception as exc:  # noqa: BLE001 — 개별 센서 읽기 실패 시 None
            name = type(sensor).__name__
            print(f"warn: {name}.read failed: {exc}")
            return None

    @staticmethod
    def _safe_read_water_level(sensor: Any) -> Any:
        """수위 센서는 뜨개 출렁임을 흡수하는 read_stable(다수결)로 읽는다."""
        if sensor is None:
            return None
        try:
            return sensor.read_stable()
        except Exception as exc:  # noqa: BLE001 — 개별 센서 읽기 실패 시 None
            name = type(sensor).__name__
            print(f"warn: {name}.read_stable failed: {exc}")
            return None

    def close(self) -> None:
        for sensor in (
            self.sensors.climate,
            self.sensors.light,
            self.sensors.soil,
            self.sensors.water_level,
        ):
            close = getattr(sensor, "close", None)
            if callable(close):
                close()
        if self.camera is not None:
            self.camera.close()
