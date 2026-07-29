from __future__ import annotations

from .base import SoilReading, SoilSensor


class ADS1115SoilSensor(SoilSensor):
    """
    아날로그 토양수분 센서 + ADS1115 ADC.

    라즈베리파이/젯슨에는 내장 ADC가 없어 ADS1115(또는 동급)가 필요합니다.
    센서 AO → ADS1115 A0(기본), VCC 3.3V 또는 5V, GND 공통.
    """

    CONVERSION_REG = 0x00
    CONFIG_REG = 0x01

    # Single-shot, PGA ±4.096V, 128SPS
    MUX = {
        0: 0x4000,  # AIN0 vs GND
        1: 0x5000,
        2: 0x6000,
        3: 0x7000,
    }

    def __init__(
        self,
        bus: int = 1,
        address: int = 0x48,
        channel: int = 0,
        dry_raw: int = 20000,
        wet_raw: int = 8000,
    ) -> None:
        from smbus2 import SMBus

        if channel not in self.MUX:
            raise ValueError("channel must be 0..3")
        self._bus = SMBus(bus)
        self._address = address
        self._channel = channel
        self._dry_raw = dry_raw
        self._wet_raw = wet_raw

    def _read_raw(self) -> int:
        import time

        config = (
            0x8000  # OS: start single conversion
            | self.MUX[self._channel]
            | 0x0200  # PGA ±4.096V
            | 0x0100  # single-shot
            | 0x0080  # 128 SPS
            | 0x0003  # disable comparator
        )
        self._bus.write_i2c_block_data(
            self._address,
            self.CONFIG_REG,
            [(config >> 8) & 0xFF, config & 0xFF],
        )
        time.sleep(0.01)
        data = self._bus.read_i2c_block_data(self._address, self.CONVERSION_REG, 2)
        value = (data[0] << 8) | data[1]
        if value & 0x8000:
            value -= 0x10000
        return value

    def _to_percent(self, raw: int) -> float:
        # 용량식/저항식 모두 보통 건조일수록 raw가 큼(또는 반대). dry/wet를 실측으로 맞춤.
        dry = float(self._dry_raw)
        wet = float(self._wet_raw)
        if dry == wet:
            return 0.0
        pct = (dry - raw) / (dry - wet) * 100.0
        return max(0.0, min(100.0, pct))

    def read(self) -> SoilReading:
        raw = self._read_raw()
        return SoilReading(raw=raw, moisture_pct=round(self._to_percent(raw), 1))

    def close(self) -> None:
        self._bus.close()
