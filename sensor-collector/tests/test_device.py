from __future__ import annotations

from src.device import resolve_device_id


def test_resolve_device_id_prefers_env(monkeypatch):
    monkeypatch.setenv("DEVICE_ID", "env-device")
    assert resolve_device_id("config-device") == "env-device"


def test_resolve_device_id_uses_config_when_not_auto(monkeypatch):
    monkeypatch.delenv("DEVICE_ID", raising=False)
    assert resolve_device_id("pi-fixed") == "pi-fixed"


def test_resolve_device_id_auto_falls_back_without_serial(monkeypatch):
    monkeypatch.delenv("DEVICE_ID", raising=False)
    monkeypatch.setattr("src.device.read_pi_serial", lambda: None)
    assert resolve_device_id("auto") == "unknown-device"


def test_resolve_device_id_auto_uses_serial(monkeypatch):
    monkeypatch.delenv("DEVICE_ID", raising=False)
    monkeypatch.setattr("src.device.read_pi_serial", lambda: "10000000abcdef01")
    assert resolve_device_id("auto") == "10000000abcdef01"
