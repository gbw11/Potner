from __future__ import annotations

import csv
from pathlib import Path
from typing import Any


class CsvStorage:
    FIELDNAMES = [
        "timestamp",
        "temperature_c",
        "humidity_pct",
        "light_lux",
        "soil_raw",
        "soil_moisture_pct",
        "water_present",
        "camera_path",
        "camera_ok",
    ]

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self._write_header()
        else:
            self._ensure_header()

    def _write_header(self) -> None:
        with self.path.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=self.FIELDNAMES)
            writer.writeheader()

    def _ensure_header(self) -> None:
        with self.path.open(encoding="utf-8") as f:
            reader = csv.reader(f)
            header = next(reader, None)
        if header != self.FIELDNAMES:
            # Keep old rows under *.bak and start a fresh schema file
            bak = self.path.with_suffix(self.path.suffix + ".bak")
            if self.path.exists():
                self.path.replace(bak)
            self._write_header()

    def write(self, row: dict[str, Any]) -> None:
        with self.path.open("a", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=self.FIELDNAMES)
            writer.writerow({key: row.get(key) for key in self.FIELDNAMES})
