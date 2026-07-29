from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class DispenseResult:
    requested_ml: float
    dispensed_ml: float
    duration_sec: float
    capped: bool  # max_run_sec 안전 상한에 걸려 요청량보다 적게 급수됨


class WaterPump(ABC):
    """급수 펌프 공통 인터페이스.

    급수량 제어는 유량 보정값 기반 시간 제어:
        duration = ml / flow_ml_per_sec + startup_sec
    startup_sec 은 펌프가 정격 유량에 도달하기까지의 시동 손실 보정값.
    두 값 모두 `python -m cli.water calibrate` 로 실측해 config 에 반영한다.
    호스가 비어 있으면 첫 급수가 모자라므로 사용 전 prime 으로 호스를 채울 것.

    서버가 계산한 필요 급수량(ml)은 dispense_ml(ml) 로 전달하면 된다.
    """

    def __init__(self, flow_ml_per_sec: float, max_run_sec: float, startup_sec: float = 0.0) -> None:
        if flow_ml_per_sec <= 0:
            raise ValueError(f"flow_ml_per_sec 는 0보다 커야 합니다: {flow_ml_per_sec}")
        if max_run_sec <= 0:
            raise ValueError(f"max_run_sec 는 0보다 커야 합니다: {max_run_sec}")
        if startup_sec < 0:
            raise ValueError(f"startup_sec 는 0 이상이어야 합니다: {startup_sec}")
        self.flow_ml_per_sec = float(flow_ml_per_sec)
        self.max_run_sec = float(max_run_sec)
        self.startup_sec = float(startup_sec)

    def duration_for_ml(self, ml: float) -> float:
        return float(ml) / self.flow_ml_per_sec + self.startup_sec

    def ml_for_duration(self, seconds: float) -> float:
        return max(0.0, float(seconds) - self.startup_sec) * self.flow_ml_per_sec

    def dispense_ml(self, ml: float) -> DispenseResult:
        """ml 만큼 급수. max_run_sec 를 넘는 요청은 상한까지만 가동."""
        if ml <= 0:
            raise ValueError(f"급수량은 0보다 커야 합니다: {ml}")
        duration = self.duration_for_ml(ml)
        capped = duration > self.max_run_sec
        run_sec = min(duration, self.max_run_sec)
        try:
            self._run(run_sec)
        finally:
            self.stop()
        return DispenseResult(
            requested_ml=float(ml),
            dispensed_ml=round(self.ml_for_duration(run_sec), 1),
            duration_sec=round(run_sec, 2),
            capped=capped,
        )

    def run_for(self, seconds: float) -> None:
        """시간 지정 가동 (보정/테스트용). max_run_sec 상한 적용."""
        if seconds <= 0:
            raise ValueError(f"가동 시간은 0보다 커야 합니다: {seconds}")
        try:
            self._run(min(float(seconds), self.max_run_sec))
        finally:
            self.stop()

    @abstractmethod
    def _run(self, seconds: float) -> None:
        """seconds 동안 펌프 가동 (blocking). stop() 은 호출부가 보장."""
        raise NotImplementedError

    @abstractmethod
    def stop(self) -> None:
        raise NotImplementedError

    def close(self) -> None:  # noqa: B027 — GPIO 구현체만 자원 해제 필요
        pass
