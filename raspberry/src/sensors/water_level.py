"""수위 센서 — 플로트 스위치 (5CFS-YZ-1, NO타입, devicemart 1346031).

자석 뜨개가 리드 스위치를 여닫는 건식 접점 2선식이다. 아날로그 센서가 아니므로
ADC(ADS1115) 불필요, 전원(VCC) 배선도 없다 — GND + GPIO 두 선이 전부다.

배선 (급수대 1호기):
  센서 한 선 → 물리 9번 핀 (GND)
  센서 다른 선 → 물리 13번 핀 (BCM GPIO27)
  극성 없음 (스위치라 두 선을 바꿔 꽂아도 동일).
  내부 풀업(3.3V)으로 읽으므로 외부 저항·전원 불필요. 5V 연결 금지 사유도 없음(연결 자체가 없음).

다른 회로와의 독립성: GPIO27은 펌프(17/18)·팬(23)·DHT(4)·I2C(2/3)와 겹치지 않고,
건식 접점이라 전기적으로도 분리된다. collector 서비스가 떠 있어도 이 센서만 따로 읽을 수 있다.

해석:
  NO 타입 원래 가정은 "접점 닫힘 = 뜨개 올라감 = 물 있음"이지만,
  **실물 확인(2026-08-03) 결과 이 장착에서는 반대** — 뜨개가 내려가면(물 없음) 접점이 닫힌다.
  그래서 프로젝트 기본값은 invert=True (닫힘 = 물 없음). 센서를 다른 방향으로
  재장착하면 invert=False 로 되돌려 `cli/water_level.py watch` 로 재확인할 것.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Callable, Optional, Sequence

log = logging.getLogger(__name__)

DEFAULT_PIN = 27  # BCM. 물리 13번 핀
DEFAULT_BOUNCE_SEC = 0.05  # 리드 스위치 채터링 흡수
# 실물 확인(2026-08-03): 이 장착에서는 접점 닫힘 = 물 없음 → 해석을 뒤집는다
DEFAULT_INVERT = True
# read_stable 기본값: 0.06s 간격 5표 다수결 (~0.24s) — 뜨개 출렁임 순간값 흡수
DEFAULT_STABLE_SAMPLES = 5
DEFAULT_STABLE_INTERVAL_SEC = 0.06


@dataclass
class WaterLevelReading:
    raw_closed: Optional[bool]  # 접점 닫힘(GPIO LOW) 여부 — 해석 전 원시값
    water_present: Optional[bool]  # invert 반영 후 "물 있음" 판정

    def to_dict(self) -> dict:
        return {"raw_closed": self.raw_closed, "water_present": self.water_present}


class FloatSwitchSensor:
    """gpiozero Button 으로 플로트 스위치 접점을 읽는다 (풀업 입력)."""

    def __init__(
        self,
        pin: int = DEFAULT_PIN,
        *,
        invert: bool = DEFAULT_INVERT,
        bounce_sec: float = DEFAULT_BOUNCE_SEC,
    ) -> None:
        try:
            from gpiozero import Button
        except ImportError as exc:
            raise ImportError(
                "수위 센서 읽기에는 gpiozero 가 필요합니다. "
                "Raspberry Pi OS: sudo apt install -y python3-gpiozero "
                "(venv 는 --system-site-packages 권장)"
            ) from exc

        self._pin = pin
        self._invert = invert
        # pull_up=True: 접점이 GND 로 닫히면 is_pressed=True (LOW)
        self._button = Button(pin, pull_up=True, bounce_time=bounce_sec)
        log.info(f"[water-level] float switch ready pin=GPIO{pin} invert={invert}")

    @property
    def pin(self) -> int:
        return self._pin

    def read(self) -> WaterLevelReading:
        closed = bool(self._button.is_pressed)
        return WaterLevelReading(
            raw_closed=closed,
            water_present=closed != self._invert,
        )

    def read_stable(
        self,
        *,
        samples: int = DEFAULT_STABLE_SAMPLES,
        sample_interval_sec: float = DEFAULT_STABLE_INTERVAL_SEC,
        sleep_fn: Callable[[float], None] = time.sleep,
    ) -> WaterLevelReading:
        """짧은 구간 다수결로 뜨개 출렁임을 흡수한 1회 판정.

        수집 루프처럼 '지금 한 번' 읽는 경로용. 감시(watch)처럼 연속 관찰하는
        경로는 CLI 쪽 유지시간(hold) 필터를 쓴다.
        """
        samples = max(1, int(samples))
        closed_votes = 0
        for i in range(samples):
            if bool(self._button.is_pressed):
                closed_votes += 1
            if i < samples - 1:
                sleep_fn(sample_interval_sec)
        closed = closed_votes * 2 > samples  # 과반
        return WaterLevelReading(
            raw_closed=closed,
            water_present=closed != self._invert,
        )

    def close(self) -> None:
        # 입력 전용이라 펌프식 풀다운 주차는 불필요 — 해제만 하면 안전하다.
        try:
            self._button.close()
        except Exception as exc:  # noqa: BLE001 — 해제 실패해도 종료는 계속
            log.warning(f"[water-level] GPIO{self._pin} 해제 실패: {exc}")


class MockFloatSwitchSensor:
    """PC(mock) 검증용. values 시퀀스를 순서대로 반환하고 마지막 값을 유지한다."""

    def __init__(
        self,
        values: Optional[Sequence[bool]] = None,
        *,
        invert: bool = DEFAULT_INVERT,
        toggle: bool = False,
    ) -> None:
        self._values = list(values) if values is not None else [True]
        self._invert = invert
        self._toggle = toggle  # True 면 read 마다 닫힘/열림 교대 (watch 흐름 확인용)
        self._index = 0

    @property
    def pin(self) -> int:
        return -1  # mock 표시

    def read(self) -> WaterLevelReading:
        if self._toggle:
            closed = self._index % 2 == 0
            self._index += 1
        else:
            closed = self._values[min(self._index, len(self._values) - 1)]
            self._index += 1
        return WaterLevelReading(
            raw_closed=closed,
            water_present=closed != self._invert,
        )

    def read_stable(self, **_kwargs) -> WaterLevelReading:
        # mock 은 노이즈가 없으므로 1회 읽기와 동일 (시그니처만 실센서와 맞춘다)
        return self.read()

    def close(self) -> None:
        pass
