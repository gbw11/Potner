from __future__ import annotations

from .base import Fan
from .pump import _park_with_pulldown


class MosfetFan(Fan):
    """IRF520 MOSFET 드라이버 모듈로 시로코(블로워) 팬 구동.

    배선 (BCM 기준):
      GPIO23 (물리 16번 핀) → 모듈 SIG, Pi GND → 모듈 GND
      팬 → 모듈 V+/V- (출력), 팬용 12V 외부 전원 → 모듈 VIN/GND
      외부 전원 GND 는 Pi 와 공통 연결 필수
    속도(송풍량)는 SIG 핀 PWM duty(0~100%)로 조절 — L298N 불필요.

    참고: IRF520 은 3.3V 게이트에서 완전히 켜지지 않을 수 있어
    최대 풍량이 소폭 낮거나 모듈이 미지근해질 수 있다 (소전류 팬은 실용상 무방).
    """

    def __init__(self, pin: int = 23, pwm_hz: float = 100.0) -> None:
        try:
            from gpiozero import PWMOutputDevice
        except ImportError as exc:
            raise ImportError(
                "팬 구동에는 gpiozero 가 필요합니다. "
                "Raspberry Pi OS: sudo apt install -y python3-gpiozero"
            ) from exc

        self._pin = pin
        self._out = PWMOutputDevice(pin, frequency=int(pwm_hz), initial_value=0.0)

    def _apply_speed(self, speed_pct: float) -> None:
        self._out.value = speed_pct / 100.0

    def close(self) -> None:
        self.off()
        self._out.close()
        _park_with_pulldown([self._pin])


class MockFan(Fan):
    """PC(mock) 모드용. 속도 변경 이력만 기록한다."""

    def __init__(self) -> None:
        self.speed_pct = 0.0
        self.speed_history: list[float] = []

    def _apply_speed(self, speed_pct: float) -> None:
        self.speed_pct = speed_pct
        self.speed_history.append(speed_pct)
        print(f"[mock fan] speed={speed_pct:.0f}%")
