from __future__ import annotations

import logging

from .base import WaterPump

log = logging.getLogger(__name__)


class MockWaterPump(WaterPump):
    """PC(mock) 모드용. 실제 대기 없이 가동 이력만 기록한다."""

    def __init__(
        self,
        flow_ml_per_sec: float = 25.0,
        max_run_sec: float = 30.0,
        startup_sec: float = 0.0,
        min_run_sec: float = 0.0,
    ) -> None:
        super().__init__(
            flow_ml_per_sec=flow_ml_per_sec,
            max_run_sec=max_run_sec,
            startup_sec=startup_sec,
            min_run_sec=min_run_sec,
        )
        self.run_history: list[float] = []
        self.running = False

    def _run(self, seconds: float) -> None:
        self.running = True
        self.run_history.append(round(seconds, 2))
        log.info(f"[mock pump] {seconds:.2f}s 가동 (~{self.ml_for_duration(seconds):.0f} ml)")

    def stop(self) -> None:
        self.running = False
