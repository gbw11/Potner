from __future__ import annotations

from src.collector import Collector


def _mock_config(tmp_path) -> dict:
    return {
        "platform": "mock",
        "storage": {"path": str(tmp_path / "readings.csv")},
        "sensors": {
            "climate": {"enabled": True},
            "light": {"enabled": True},
            "soil": {"enabled": True},
        },
        "camera": {"enabled": False},
    }


def test_read_once_writes_csv_row(tmp_path):
    collector = Collector(_mock_config(tmp_path))
    try:
        row = collector.read_once()
    finally:
        collector.close()

    assert row["temperature_c"] is not None
    assert row["humidity_pct"] is not None
    assert row["light_lux"] is not None
    assert row["soil_raw"] is not None
    assert row["soil_moisture_pct"] is not None
    assert (tmp_path / "readings.csv").exists()


def test_should_capture_every_n(tmp_path):
    config = _mock_config(tmp_path)
    config["camera"] = {
        "enabled": False,
        "capture_with_read": True,
        "capture_every_n": 2,
    }
    collector = Collector(config)

    assert collector._should_capture(None) is False
    assert collector._should_capture(None) is True
    assert collector._should_capture(True) is True
    assert collector._should_capture(False) is False
