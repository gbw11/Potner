from __future__ import annotations

from typing import Any, Optional

from .base import Camera
from .camera import Picamera2Camera
from .mock import MockCamera
from .store import CameraStore


def build_camera(config: dict[str, Any]) -> Optional[Camera]:
    cam_cfg = config.get("camera", {}) or {}
    if not cam_cfg.get("enabled", False):
        return None

    platform = str(config.get("platform", "mock")).lower()
    width = int(cam_cfg.get("width", 1920))
    height = int(cam_cfg.get("height", 1080))
    driver = str(cam_cfg.get("driver", "auto")).lower()

    if driver == "mock" or platform == "mock":
        return MockCamera(width=width, height=height)

    if driver in {"auto", "picamera2", "ov5647", "sunfounder"}:
        return Picamera2Camera(
            width=width,
            height=height,
            camera_num=int(cam_cfg.get("camera_num", 0)),
            settle_sec=float(cam_cfg.get("settle_sec", 1.0)),
            exposure_value=float(cam_cfg.get("exposure_value", 1.0)),
            brightness=float(cam_cfg.get("brightness", 0.1)),
            contrast=float(cam_cfg.get("contrast", 1.1)),
            saturation=float(cam_cfg.get("saturation", 1.05)),
            awb_mode=str(cam_cfg.get("awb_mode", "auto")),
            colour_gains=cam_cfg.get("colour_gains"),
        )

    raise ValueError(f"Unknown camera driver: {driver}")


def build_camera_store(config: dict[str, Any]) -> CameraStore:
    cam_cfg = config.get("camera", {}) or {}
    # events.path 가 있으면 촬영 이력도 남긴다 (없으면 앱 로그에만 기록)
    events_path = (config.get("events") or {}).get("path")
    event_store = None
    if events_path:
        from ..events.store import EventStore

        event_store = EventStore(events_path)
    return CameraStore(
        cam_cfg.get("path", "data/camera"),
        event_store=event_store,
        trigger="collector",
    )
