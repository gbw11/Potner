"""비용이 큰 frontier 분석을 ROS executor 밖에서 실행합니다."""

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from threading import Event
from typing import Callable

from potner_exploration.frontier_detector import (
    FrontierAnalysis,
    FrontierAnalysisCancelled,
    FrontierConfig,
    GridMap,
    Point,
    analyze_frontiers,
)


AnalysisCancelled = FrontierAnalysisCancelled


@dataclass(frozen=True)
class AnalysisRequest:
    revision: int
    grid: GridMap
    robot_x: float
    robot_y: float
    config: FrontierConfig
    blocked_points: tuple[Point, ...]


@dataclass(frozen=True)
class AnalysisResult:
    revision: int
    analysis: FrontierAnalysis


Analyzer = Callable[[AnalysisRequest, Callable[[], bool]], FrontierAnalysis]


class FrontierAnalysisWorker:
    def __init__(self, analyzer: Analyzer | None = None):
        self._analyzer = analyzer or _analyze_request
        self._executor = ThreadPoolExecutor(
            max_workers=1,
            thread_name_prefix="frontier-analysis",
        )
        self._future = None
        self._cancel_event = None
        self._active_revision = None
        self._pending_request = None

    @property
    def busy(self) -> bool:
        return self._future is not None

    def request(self, request: AnalysisRequest) -> bool:
        if self._future is not None:
            if request.revision != self._active_revision:
                self._pending_request = request
            return False
        self._start(request)
        return True

    def _start(self, request: AnalysisRequest):
        self._cancel_event = Event()
        self._active_revision = request.revision
        self._future = self._executor.submit(
            self._execute,
            request,
            self._cancel_event,
        )

    def poll(self) -> AnalysisResult | None:
        if self._future is None or not self._future.done():
            return None
        future = self._future
        cancel_event = self._cancel_event
        self._future = None
        self._cancel_event = None
        self._active_revision = None
        try:
            result = future.result()
        except AnalysisCancelled:
            result = None
        if cancel_event is not None and cancel_event.is_set():
            result = None

        pending = self._pending_request
        self._pending_request = None
        if pending is not None:
            self._start(pending)
        return result

    def cancel(self):
        self._pending_request = None
        if self._future is not None:
            if self._cancel_event is not None:
                self._cancel_event.set()
            if self._future.cancel():
                self._future = None
                self._cancel_event = None
                self._active_revision = None

    def shutdown(self):
        self.cancel()
        self._executor.shutdown(wait=True, cancel_futures=True)
        self._future = None
        self._cancel_event = None
        self._active_revision = None

    def _execute(
        self,
        request: AnalysisRequest,
        cancel_event: Event,
    ) -> AnalysisResult:
        return AnalysisResult(
            revision=request.revision,
            analysis=self._analyzer(request, cancel_event.is_set),
        )


def _analyze_request(
    request: AnalysisRequest,
    should_cancel: Callable[[], bool],
) -> FrontierAnalysis:
    return analyze_frontiers(
        request.grid,
        request.robot_x,
        request.robot_y,
        request.config,
        request.blocked_points,
        should_cancel=should_cancel,
    )
