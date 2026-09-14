"""Raspberry Pi / host device identity helpers."""

from __future__ import annotations

import os
from pathlib import Path


def read_pi_serial() -> str | None:
    """Read board serial (unique per Raspberry Pi)."""
    candidates = (
        Path("/sys/firmware/devicetree/base/serial-number"),
        Path("/proc/device-tree/serial-number"),
    )
    for path in candidates:
        if not path.exists():
            continue
        value = path.read_text(encoding="utf-8", errors="ignore").strip("\x00").strip()
        if value:
            return value

    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.exists():
        for line in cpuinfo.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.lower().startswith("serial"):
                parts = line.split(":", 1)
                if len(parts) == 2:
                    value = parts[1].strip()
                    if value and value.lower() not in {"0000000000000000"}:
                        return value
    return None


def resolve_device_id(configured: str | None = None) -> str:
    """
    Resolve outbound device id.

    Priority:
      1) DEVICE_ID env
      2) explicit config (not empty / not 'auto')
      3) Raspberry Pi board serial
      4) fallback 'unknown-device'
    """
    env = (os.getenv("DEVICE_ID") or "").strip()
    if env:
        return env

    cfg = (configured or "").strip()
    if cfg and cfg.lower() != "auto":
        return cfg

    serial = read_pi_serial()
    if serial:
        return serial

    return "unknown-device"
