from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from .base import ClimateSensor, LightSensor, SoilSensor
from .bh1750 import BH1750Sensor
from .climate import AHT20ClimateSensor, DHTClimateSensor
from .mock import MockClimateSensor, MockLightSensor, MockSoilSensor
from .soil import ADS1115SoilSensor


@dataclass
class SensorBundle:
    climate: Optional[ClimateSensor]
    light: Optional[LightSensor]
    soil: Optional[SoilSensor]


def build_sensors(config: dict[str, Any]) -> SensorBundle:
    platform = str(config.get("platform", "mock")).lower()
    bus = int(config.get("i2c_bus", 1))
    sensors_cfg = config.get("sensors", {})

    if platform == "mock":
        return SensorBundle(
            climate=MockClimateSensor() if sensors_cfg.get("climate", {}).get("enabled", True) else None,
            light=MockLightSensor() if sensors_cfg.get("light", {}).get("enabled", True) else None,
            soil=MockSoilSensor() if sensors_cfg.get("soil", {}).get("enabled", True) else None,
        )

    climate = None
    light = None
    soil = None

    climate_cfg = sensors_cfg.get("climate", {})
    if climate_cfg.get("enabled", True):
        driver = str(climate_cfg.get("driver", "dht22")).lower()
        if driver in {"dht22", "dht11"}:
            climate = DHTClimateSensor(model=driver, pin=int(climate_cfg.get("pin", 4)))
        elif driver == "aht20":
            climate = AHT20ClimateSensor(bus=bus, address=int(climate_cfg.get("address", 0x38)))
        elif driver == "mock":
            climate = MockClimateSensor()
        else:
            raise ValueError(f"Unknown climate driver: {driver}")

    light_cfg = sensors_cfg.get("light", {})
    if light_cfg.get("enabled", True):
        driver = str(light_cfg.get("driver", "bh1750")).lower()
        if driver == "bh1750":
            light = BH1750Sensor(bus=bus, address=int(light_cfg.get("address", 0x23)))
        elif driver == "mock":
            light = MockLightSensor()
        else:
            raise ValueError(f"Unknown light driver: {driver}")

    soil_cfg = sensors_cfg.get("soil", {})
    if soil_cfg.get("enabled", True):
        driver = str(soil_cfg.get("driver", "ads1115")).lower()
        if driver == "ads1115":
            soil = ADS1115SoilSensor(
                bus=bus,
                address=int(soil_cfg.get("address", 0x48)),
                channel=int(soil_cfg.get("channel", 0)),
                dry_raw=int(soil_cfg.get("dry_raw", 20000)),
                wet_raw=int(soil_cfg.get("wet_raw", 8000)),
            )
        elif driver == "mock":
            soil = MockSoilSensor()
        else:
            raise ValueError(f"Unknown soil driver: {driver}")

    return SensorBundle(climate=climate, light=light, soil=soil)
