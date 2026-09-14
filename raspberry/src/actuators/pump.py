from __future__ import annotations

import logging
import time

from .base import WaterPump

log = logging.getLogger(__name__)


def _park_with_pulldown(pins: list[int]) -> None:
    """핀 해제 후 풀다운 입력으로 '주차'.

    gpiozero 가 핀을 해제하면 floating 입력이 되어 노이즈로 hi/lo 가 흔들리고,
    L298N IN 핀에는 풀다운이 없어 프로그램 미가동 중 펌프가 오작동할 수 있다.
    풀다운 설정은 핀 해제 후에도 패드 레지스터에 유지된다.
    """
    try:
        from gpiozero import InputDevice

        for pin in pins:
            InputDevice(pin, pull_up=False).close()
    except Exception as exc:  # noqa: BLE001 — 주차 실패해도 종료는 계속
        log.warning(f"GPIO pull-down parking failed: {exc}")


class L298NWaterPump(WaterPump):
    """L298N 모터 드라이버로 다이어프램 펌프 구동.

    배선 (BCM 기준):
      GPIO17 → IN1, GPIO18 → IN2, 펌프는 OUT1/OUT2
      가동 = IN1 HIGH / IN2 LOW, 정지 = 둘 다 LOW (coast)
      전원: 모듈 +12V 단자에 펌프용 외부 전원, GND 는 Pi 와 공통 연결 필수
      ENA: 점퍼 장착 시 항상 enable (기본 구성).
           점퍼를 빼고 GPIO 에 연결하면 ena_pin 설정으로 PWM 세기 조절 가능.
           (PWM 세기를 바꾸면 유량이 달라지므로 같은 speed 로 calibrate 할 것)

    gpiozero 는 Raspberry Pi OS 기본 탑재 (Pi 5 포함 lgpio 백엔드 지원).
    """

    def __init__(
        self,
        in1_pin: int = 17,
        in2_pin: int = 18,
        ena_pin: int | None = None,
        speed: float = 1.0,
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)
        try:
            from gpiozero import DigitalOutputDevice, PWMOutputDevice
        except ImportError as exc:
            raise ImportError(
                "펌프 구동에는 gpiozero 가 필요합니다. "
                "Raspberry Pi OS: sudo apt install -y python3-gpiozero "
                "(venv 는 --system-site-packages 권장)"
            ) from exc

        self._pins = [in1_pin, in2_pin]
        self._in1 = DigitalOutputDevice(in1_pin, initial_value=False)
        self._in2 = DigitalOutputDevice(in2_pin, initial_value=False)
        self._ena = None
        if ena_pin is not None:
            self._pins.append(ena_pin)
            self._ena = PWMOutputDevice(ena_pin, initial_value=0.0)
        self._speed = min(max(float(speed), 0.0), 1.0)

    def _run(self, seconds: float) -> None:
        self._in2.off()
        self._in1.on()
        if self._ena is not None:
            self._ena.value = self._speed
        time.sleep(seconds)

    def stop(self) -> None:
        if self._ena is not None:
            self._ena.value = 0.0
        self._in1.off()
        self._in2.off()

    def close(self) -> None:
        self.stop()
        self._in1.close()
        self._in2.close()
        if self._ena is not None:
            self._ena.close()
        _park_with_pulldown(self._pins)


class RelayWaterPump(WaterPump):
    """릴레이/MOSFET 1핀으로 펌프 ON/OFF.

    active_high=False 는 low-level trigger 릴레이 모듈용.
    """

    def __init__(self, pin: int = 17, active_high: bool = True, **kwargs) -> None:
        super().__init__(**kwargs)
        try:
            from gpiozero import DigitalOutputDevice
        except ImportError as exc:
            raise ImportError(
                "펌프 구동에는 gpiozero 가 필요합니다. "
                "Raspberry Pi OS: sudo apt install -y python3-gpiozero"
            ) from exc

        self._pin = pin
        self._out = DigitalOutputDevice(pin, active_high=active_high, initial_value=False)

    def _run(self, seconds: float) -> None:
        self._out.on()
        time.sleep(seconds)

    def stop(self) -> None:
        self._out.off()

    def close(self) -> None:
        self.stop()
        self._out.close()
        _park_with_pulldown([self._pin])
