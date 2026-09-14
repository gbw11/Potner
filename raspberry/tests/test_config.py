from __future__ import annotations

import pytest

from src.config import load_config


def test_load_project_default_yaml():
    data = load_config("config/default.yaml")
    assert data["platform"] == "mock"
    assert "status_rules" in data
    assert "sensors" in data


def test_load_raspberry_pi_yaml():
    data = load_config("config/raspberry_pi.yaml")
    assert data["platform"] == "raspberry_pi"


def test_invalid_config_raises(tmp_path):
    bad = tmp_path / "bad.yaml"
    bad.write_text("- not\n- a\n- mapping\n", encoding="utf-8")

    with pytest.raises(ValueError, match="Invalid config"):
        load_config(bad)
