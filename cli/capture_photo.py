#!/usr/bin/env python3
"""Capture one photo and save under data/camera/.

- On Raspberry Pi: shoot with OV5647 → data/camera/
- On Windows PC: shoot on Pi over SSH, then sync into local data/camera/

Usage (from project root):
  python -m cli.capture_photo
  python -m cli.capture_photo --mock          # PC dummy PNG only
  python -m cli.capture_photo --local-only    # do not SSH (Pi or mock)
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

from src.config import load_config
from src.vision import build_camera, build_camera_store

REMOTE_HOST = "raspberrypi"
REMOTE_DIR = "~/sensor-collector"
LOCAL_CAMERA_DIR = Path("data/camera")


def _is_raspberry_pi() -> bool:
    return Path("/proc/device-tree/model").exists() or Path("/proc/device-tree/system").exists()


def _run_local_capture(config_path: Path, *, force_mock: bool = False) -> int:
    config = load_config(config_path)
    cam_cfg = dict(config.get("camera") or {})
    cam_cfg["enabled"] = True
    cam_cfg["path"] = str(LOCAL_CAMERA_DIR)

    if force_mock or str(config.get("platform", "")).lower() == "mock":
        cam_cfg["driver"] = "mock"
        config["platform"] = "mock"
    else:
        cam_cfg.setdefault("driver", "picamera2")

    config["camera"] = cam_cfg
    camera = build_camera(config)
    if camera is None:
        print("Camera could not be created.", file=sys.stderr)
        return 1

    store = build_camera_store(config)
    try:
        result = store.capture(camera)
    finally:
        camera.close()

    print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
    if result.ok:
        print(f"\nSaved: {result.path}")
        return 0
    print(f"\nCapture failed: {result.error}", file=sys.stderr)
    return 1


def _sync_from_pi() -> None:
    LOCAL_CAMERA_DIR.mkdir(parents=True, exist_ok=True)
    remote = f"{REMOTE_HOST}:{REMOTE_DIR}/data/camera/."
    cmd = ["scp", "-r", remote, str(LOCAL_CAMERA_DIR)]
    subprocess.run(cmd, check=True)


def _capture_on_pi_and_sync(config_name: str = "config/raspberry_pi.yaml") -> int:
    print(f"[PC] Capture on {REMOTE_HOST}, then save to {LOCAL_CAMERA_DIR}/ ...")
    remote_cmd = (
        f"cd {REMOTE_DIR} && . .venv/bin/activate && "
        f"python -m cli.capture_photo --local-only --config {config_name}"
    )
    result = subprocess.run(["ssh", REMOTE_HOST, remote_cmd])
    if result.returncode != 0:
        print("Remote capture failed.", file=sys.stderr)
        return result.returncode or 1

    _sync_from_pi()
    print(f"\nSynced to {LOCAL_CAMERA_DIR.resolve()}")
    latest = sorted(LOCAL_CAMERA_DIR.glob("frame_*.jpg"), key=lambda p: p.stat().st_mtime)
    if latest:
        print(f"Latest: {latest[-1]}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Capture photo → data/camera/")
    parser.add_argument("--config", default=None, help="Config YAML path")
    parser.add_argument("--mock", action="store_true", help="Force mock image (no Pi)")
    parser.add_argument(
        "--local-only",
        action="store_true",
        help="Capture on this machine only (used on Pi; no SSH sync)",
    )
    args = parser.parse_args()

    if args.config:
        config_path = Path(args.config)
    elif _is_raspberry_pi() or args.local_only:
        config_path = Path("config/raspberry_pi.yaml")
        if not config_path.exists():
            config_path = Path("config/default.yaml")
    else:
        config_path = Path("config/default.yaml")

    if not config_path.exists():
        print(f"Config not found: {config_path}", file=sys.stderr)
        return 1

    # Pi (or explicit local): save directly under data/camera
    if args.local_only or _is_raspberry_pi() or args.mock:
        return _run_local_capture(config_path, force_mock=args.mock)

    # Windows / other PC: shoot on Pi and sync into local data/camera
    if os.name == "nt" or sys.platform.startswith("win"):
        return _capture_on_pi_and_sync("config/raspberry_pi.yaml")

    # Linux laptop without Pi board: same remote flow
    return _capture_on_pi_and_sync("config/raspberry_pi.yaml")


if __name__ == "__main__":
    raise SystemExit(main())
