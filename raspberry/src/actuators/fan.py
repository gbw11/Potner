from __future__ import annotations

from .base import Fan
from .pump import _park_with_pulldown


class MosfetFan(Fan):
    """LR7843 MOSFET 드라이버 모듈로 시로코(블로워) 팬 구동.

    배선 (BCM 기준):
      GPIO23 (물리 16번 핀) → 모듈 PWM, Pi GND → 모듈 GND
      팬 → 모듈 출력(V+/V-), 팬용 12V 외부 전원 → 모듈 입력(VIN/GND)
      외부 전원 GND 는 Pi 와 공통 연결 필수
    속도(송풍량)는 GPIO PWM duty(0~100%)로만 조절한다 — 별도의 속도 신호선
    (4핀 PC 팬의 파란선 같은 것)은 없고, L298N 도 쓰지 않는다.

    주의: duty 를 낮추면 어느 지점 아래에서는 팬이 진동만 하고 회전하지 않는다
    (기동 토크 부족). 2026-08-05 실측: 70% = 진동만, 100% = 정상 회전 — 그래서
    실사용은 100% 고정이다. duty 를 낮출 일이 생기면 반드시 실물로 확인할 것.
    RPM 피드백선이 없어 소프트웨어는 "지시한 duty" 만 알고 실제 회전 여부를 모른다.
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
