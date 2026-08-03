from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from .base import ClimateSensor, LightSensor, SoilSensor
from .bh1750 import BH1750Sensor
from .climate import AHT20ClimateSensor, DHTClimateSensor, IIOClimateSensor
from .mock import MockClimateSensor, MockLightSensor, MockSoilSensor
from .soil import ADS1115SoilSensor
from .water_level import DEFAULT_INVERT, DEFAULT_PIN, MockFloatSwitchSensor


@dataclass
class SensorBundle:
    climate: Optional[ClimateSensor]
    light: Optional[LightSensor]
    soil: Optional[SoilSensor]
    water_level: Optional[Any] = None  # FloatSwitchSensor | MockFloatSwitchSensor


def _build_water_level(sensors_cfg: dict[str, Any], *, platform_mock: bool) -> Optional[Any]:
    """sensors.water_level 설정으로 수위 센서(플로트 스위치)를 만든다.

    기본은 꺼짐(enabled: false) — 배선 안 된 환경에서 GPIO 를 잡지 않기 위해서다.
    실패해도 다른 센서처럼 warn 후 None 으로 두어 수집 루프는 유지한다.
    """
    cfg = sensors_cfg.get("water_level", {}) or {}
    if not cfg.get("enabled", False):
        return None

    invert = bool(cfg.get("invert", DEFAULT_INVERT))
    driver = str(cfg.get("driver", "float_switch")).lower()
    if platform_mock or driver == "mock":
        # mock 기본값은 raw 접점 닫힘 고정 = (invert 켜짐 기준) "물 없음" 고정이라
        # PC 에서는 물부족→복구 흐름을 재현할 수 없다. 시퀀스/교대를 config 로 열어둔다.
        values = cfg.get("mock_values") or None
        return MockFloatSwitchSensor(
            values=[bool(v) for v in values] if values else None,
            invert=invert,
            toggle=bool(cfg.get("mock_toggle", False)),
        )

    try:
        if driver == "float_switch":
            from .water_level import FloatSwitchSensor

            return FloatSwitchSensor(pin=int(cfg.get("pin", DEFAULT_PIN)), invert=invert)
        raise ValueError(f"Unknown water_level driver: {driver}")
    except Exception as exc:  # noqa: BLE001 — 개별 센서 실패해도 수집 루프 유지
        print(f"warn: water_level sensor unavailable ({driver}): {exc}")
        return None


def build_sensors(config: dict[str, Any]) -> SensorBundle:
    platform = str(config.get("platform", "mock")).lower()
    bus = int(config.get("i2c_bus", 1))
    sensors_cfg = config.get("sensors", {})

    if platform == "mock":
        return SensorBundle(
            climate=MockClimateSensor() if sensors_cfg.get("climate", {}).get("enabled", True) else None,
            light=MockLightSensor() if sensors_cfg.get("light", {}).get("enabled", False) else None,
            soil=MockSoilSensor() if sensors_cfg.get("soil", {}).get("enabled", False) else None,
            water_level=_build_water_level(sensors_cfg, platform_mock=True),
        )

    climate = None
    light = None
    soil = None

    climate_cfg = sensors_cfg.get("climate", {})
    if climate_cfg.get("enabled", True):
        driver = str(climate_cfg.get("driver", "dht22")).lower()
        try:
            if driver in {"dht22", "dht11"}:
                climate = DHTClimateSensor(model=driver, pin=int(climate_cfg.get("pin", 4)))
            elif driver in {"iio", "dht11_iio"}:
                climate = IIOClimateSensor(
                    device_path=str(
                        climate_cfg.get("device_path", "/sys/bus/iio/devices/iio:device0")
                    )
                )
            elif driver == "aht20":
                climate = AHT20ClimateSensor(bus=bus, address=int(climate_cfg.get("address", 0x38)))
            elif driver == "mock":
                climate = MockClimateSensor()
            else:
                raise ValueError(f"Unknown climate driver: {driver}")
        except Exception as exc:  # noqa: BLE001 — 개별 센서 실패해도 수집 루프 유지
            print(f"warn: climate sensor unavailable ({driver}): {exc}")

    light_cfg = sensors_cfg.get("light", {})
    if light_cfg.get("enabled", False):
        driver = str(light_cfg.get("driver", "bh1750")).lower()
        try:
            if driver == "bh1750":
                light = BH1750Sensor(bus=bus, address=int(light_cfg.get("address", 0x23)))
            elif driver == "mock":
                light = MockLightSensor()
            else:
                raise ValueError(f"Unknown light driver: {driver}")
        except Exception as exc:  # noqa: BLE001
            print(f"warn: light sensor unavailable ({driver}): {exc}")

    soil_cfg = sensors_cfg.get("soil", {})
    if soil_cfg.get("enabled", False):
        driver = str(soil_cfg.get("driver", "ads1115")).lower()
        try:
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
        except Exception as exc:  # noqa: BLE001
            print(f"warn: soil sensor unavailable ({driver}): {exc}")

    water_level = _build_water_level(sensors_cfg, platform_mock=False)

    return SensorBundle(climate=climate, light=light, soil=soil, water_level=water_level)
