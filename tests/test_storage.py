from __future__ import annotations

import csv

from src.storage import CsvStorage


def test_write_creates_header_and_row(tmp_path):
    path = tmp_path / "readings.csv"
    storage = CsvStorage(path)

    storage.write(
        {
            "timestamp": "2026-07-27T10:00:00+09:00",
            "temperature_c": 22.5,
            "humidity_pct": 50.0,
            "light_lux": 300.0,
            "soil_raw": 14000,
            "soil_moisture_pct": 48.0,
            "extra_ignored": True,
        }
    )

    with path.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    assert rows[0]["temperature_c"] == "22.5"
    assert rows[0]["soil_moisture_pct"] == "48.0"
    assert "extra_ignored" not in rows[0]


def test_old_header_is_backed_up(tmp_path):
    path = tmp_path / "readings.csv"
    path.write_text("old,header\n1,2\n", encoding="utf-8")

    storage = CsvStorage(path)
    storage.write({"timestamp": "t", "temperature_c": 1})

    bak = path.with_suffix(path.suffix + ".bak")
    assert bak.exists()
    with path.open(encoding="utf-8") as f:
        header = next(csv.reader(f))
    assert header == CsvStorage.FIELDNAMES
