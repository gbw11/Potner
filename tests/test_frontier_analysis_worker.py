import threading
import time

from potner_exploration.frontier_analysis_worker import (
    AnalysisCancelled,
    AnalysisRequest,
    FrontierAnalysisWorker,
)
from potner_exploration.frontier_detector import (
    FrontierConfig,
    GridMap,
    analyze_frontiers,
)


def test_worker_returns_analysis_without_blocking_requester():
    grid = GridMap(
        width=3,
        height=1,
        resolution=1.0,
        origin_x=0.0,
        origin_y=0.0,
        data=(0, 0, -1),
    )
    worker = FrontierAnalysisWorker()
    try:
        accepted = worker.request(
            AnalysisRequest(
                revision=7,
                grid=grid,
                robot_x=0.5,
                robot_y=0.5,
                config=FrontierConfig(
                    min_cluster_size=1,
                    clearance_m=0.0,
                ),
                blocked_points=(),
            )
        )

        assert accepted
        result = wait_for_result(worker)
        assert result.revision == 7
        assert len(result.analysis.frontiers) == 1
    finally:
        worker.shutdown()


def test_worker_rejects_duplicate_request_while_analysis_is_pending():
    grid = GridMap(1, 1, 1.0, 0.0, 0.0, (0,))
    request = AnalysisRequest(
        revision=1,
        grid=grid,
        robot_x=0.5,
        robot_y=0.5,
        config=FrontierConfig(clearance_m=0.0),
        blocked_points=(),
    )
    started = threading.Event()
    release = threading.Event()

    def slow_analysis(pending, _cancelled):
        started.set()
        assert release.wait(timeout=2.0)
        return analyze_frontiers(
            pending.grid,
            pending.robot_x,
            pending.robot_y,
            pending.config,
            pending.blocked_points,
        )

    worker = FrontierAnalysisWorker(analyzer=slow_analysis)
    try:
        assert worker.request(request)
        assert started.wait(timeout=2.0)
        assert not worker.request(request)
        release.set()
        wait_for_result(worker)
    finally:
        release.set()
        worker.shutdown()


def test_worker_coalesces_pending_requests_to_the_latest_revision():
    grid = GridMap(1, 1, 1.0, 0.0, 0.0, (0,))
    started = threading.Event()
    release = threading.Event()
    calls = 0

    def slow_first_analysis(pending, _cancelled):
        nonlocal calls
        calls += 1
        if calls == 1:
            started.set()
            assert release.wait(timeout=2.0)
        return analyze_frontiers(
            pending.grid,
            pending.robot_x,
            pending.robot_y,
            pending.config,
            pending.blocked_points,
        )

    def request(revision):
        return AnalysisRequest(
            revision=revision,
            grid=grid,
            robot_x=0.5,
            robot_y=0.5,
            config=FrontierConfig(clearance_m=0.0),
            blocked_points=(),
        )

    worker = FrontierAnalysisWorker(analyzer=slow_first_analysis)
    try:
        assert worker.request(request(1))
        assert started.wait(timeout=2.0)
        assert not worker.request(request(2))
        assert not worker.request(request(3))
        release.set()

        assert wait_for_result(worker).revision == 1
        assert wait_for_result(worker).revision == 3
        assert calls == 2
    finally:
        release.set()
        worker.shutdown()


def test_running_analysis_cooperatively_stops_without_becoming_orphaned():
    grid = GridMap(1, 1, 1.0, 0.0, 0.0, (0,))
    started = threading.Event()
    stopped = threading.Event()

    def cancellable_analysis(_pending, cancelled):
        started.set()
        while not cancelled():
            time.sleep(0.001)
        stopped.set()
        raise AnalysisCancelled()

    worker = FrontierAnalysisWorker(analyzer=cancellable_analysis)
    try:
        worker.request(
            AnalysisRequest(
                revision=1,
                grid=grid,
                robot_x=0.5,
                robot_y=0.5,
                config=FrontierConfig(clearance_m=0.0),
                blocked_points=(),
            )
        )
        assert started.wait(timeout=2.0)

        worker.cancel()
        worker.shutdown()

        assert stopped.wait(timeout=0.2)
        assert not worker.busy
    finally:
        worker.shutdown()


def wait_for_result(worker):
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline:
        result = worker.poll()
        if result is not None:
            return result
        time.sleep(0.001)
    raise AssertionError("frontier analysis worker did not finish")
