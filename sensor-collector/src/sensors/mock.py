from __future__ import annotations

import random

from .base import ClimateReading, ClimateSensor, LightReading, LightSensor, SoilReading, SoilSensor


class MockClimateSensor(ClimateSensor):
    def read(self) -> ClimateReading:
        return ClimateReading(
            temperature_c=round(22.0 + random.uniform(-1.5, 1.5), 2),
            humidity_pct=round(55.0 + random.uniform(-5.0, 5.0), 2),
        )


class MockLightSensor(LightSensor):
    def read(self) -> LightReading:
        return LightReading(lux=round(random.uniform(80.0, 650.0), 1))


class MockSoilSensor(SoilSensor):
    def read(self) -> SoilReading:
        raw = random.randint(9000, 18000)
        pct = max(0.0, min(100.0, (20000 - raw) / (20000 - 8000) * 100.0))
        return SoilReading(raw=raw, moisture_pct=round(pct, 1))
