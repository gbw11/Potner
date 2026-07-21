from __future__ import annotations

from .base import LightReading, LightSensor


class BH1750Sensor(LightSensor):
    """GY-302 / BH1750 디지털 조도 센서 (I2C)."""

    POWER_ON = 0x01
    RESET = 0x07
    CONT_HRES = 0x10  # 1 lx resolution, ~120ms

    def __init__(self, bus: int = 1, address: int = 0x23) -> None:
        from smbus2 import SMBus

        self._bus_num = bus
        self._address = address
        self._bus = SMBus(bus)
        self._bus.write_byte(self._address, self.POWER_ON)
        self._bus.write_byte(self._address, self.RESET)
        self._bus.write_byte(self._address, self.CONT_HRES)

    def read(self) -> LightReading:
        import time

        time.sleep(0.18)
        data = self._bus.read_i2c_block_data(self._address, self.CONT_HRES, 2)
        raw = (data[0] << 8) | data[1]
        lux = raw / 1.2
        return LightReading(lux=round(lux, 1))

    def close(self) -> None:
        self._bus.close()
