from __future__ import annotations

import time
from pathlib import Path

from .base import ClimateReading, ClimateSensor


class IIOClimateSensor(ClimateSensor):
    """dtoverlay dht11 등으로 노출된 IIO 온습도 (/sys/bus/iio/...)."""

    def __init__(self, device_path: str = "/sys/bus/iio/devices/iio:device0") -> None:
        self._device = Path(device_path)
        if not self._device.exists():
            raise FileNotFoundError(f"IIO climate device not found: {self._device}")

    def _read_milli(self, name: str) -> float | None:
        path = self._device / name
        for _ in range(5):
            try:
                return int(path.read_text().strip()) / 1000.0
            except (OSError, ValueError):
                time.sleep(0.5)
        return None

    def read(self) -> ClimateReading:
        temp = self._read_milli("in_temp_input")
        hum = self._read_milli("in_humidityrelative_input")
        return ClimateReading(
            temperature_c=None if temp is None else round(float(temp), 2),
            humidity_pct=None if hum is None else round(float(hum), 2),
        )


class DHTClimateSensor(ClimateSensor):
    """DHT11 / DHT22 온습도 센서 (GPIO one-wire)."""

    def __init__(self, model: str, pin: int) -> None:
        try:
            import adafruit_dht
            import board
        except ImportError as exc:
            raise ImportError(
                "DHT 사용 시 adafruit-circuitpython-dht 와 Adafruit-Blinka 가 필요합니다. "
                "pip install adafruit-circuitpython-dht Adafruit-Blinka"
            ) from exc

        pin_attr = f"D{pin}"
        if not hasattr(board, pin_attr):
            raise ValueError(f"보드에 GPIO {pin_attr} 가 없습니다.")

        gpio = getattr(board, pin_attr)
        model = model.lower()
        if model == "dht22":
            self._dht = adafruit_dht.DHT22(gpio, use_pulseio=False)
        elif model == "dht11":
            self._dht = adafruit_dht.DHT11(gpio, use_pulseio=False)
        else:
            raise ValueError(f"지원하지 않는 DHT 모델: {model}")

    def read(self) -> ClimateReading:
        try:
            temperature = self._dht.temperature
            humidity = self._dht.humidity
        except RuntimeError:
            # DHT는 간헐적 읽기 실패가 흔함
            return ClimateReading(temperature_c=None, humidity_pct=None)
        return ClimateReading(
            temperature_c=None if temperature is None else round(float(temperature), 2),
            humidity_pct=None if humidity is None else round(float(humidity), 2),
        )


class AHT20ClimateSensor(ClimateSensor):
    """AHT20 온습도 센서 (I2C). 키트 구성품이 AHT 계열일 때 사용."""

    def __init__(self, bus: int = 1, address: int = 0x38) -> None:
        from smbus2 import SMBus

        self._address = address
        self._bus = SMBus(bus)
        # Soft reset + init
        self._bus.write_i2c_block_data(self._address, 0xBA, [])
        time.sleep(0.02)
        self._bus.write_i2c_block_data(self._address, 0xBE, [0x08, 0x00])
        time.sleep(0.01)

    def read(self) -> ClimateReading:
        self._bus.write_i2c_block_data(self._address, 0xAC, [0x33, 0x00])
        time.sleep(0.08)
        data = self._bus.read_i2c_block_data(self._address, 0x00, 6)
        if data[0] & 0x80:
            return ClimateReading(temperature_c=None, humidity_pct=None)

        humidity_raw = ((data[1] << 12) | (data[2] << 4) | (data[3] >> 4)) & 0xFFFFF
        temp_raw = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5]
        humidity = humidity_raw / 1048576 * 100
        temperature = temp_raw / 1048576 * 200 - 50
        return ClimateReading(
            temperature_c=round(temperature, 2),
            humidity_pct=round(humidity, 2),
        )

    def close(self) -> None:
        self._bus.close()
