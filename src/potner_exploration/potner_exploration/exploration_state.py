"""ROS와 독립적인 자율 탐색 상태 머신."""

from dataclasses import dataclass
from enum import Enum, auto


Point = tuple[float, float]


class ExplorationState(Enum):
    WAITING_FOR_SYSTEM = auto()
    WAITING_FOR_SENSORS = auto()
    SELECTING_FRONTIER = auto()
    NAVIGATING = auto()
    RECOVERY = auto()
    VERIFYING_COMPLETE = auto()
    SAVING_MAP = auto()
    COMPLETED = auto()
    FAILED = auto()


@dataclass(frozen=True)
class ExplorationPolicy:
    goal_timeout_s: float = 90.0
    completion_hold_s: float = 15.0
    max_consecutive_failures: int = 5
    max_exploration_s: float = 1800.0
    blacklist_ttl_s: float = 120.0
    save_timeout_s: float = 30.0
    sensor_recovery_timeout_s: float = 10.0

    def __post_init__(self):
        if self.goal_timeout_s <= 0.0:
            raise ValueError("goal_timeout_s must be positive")
        if self.completion_hold_s <= 0.0:
            raise ValueError("completion_hold_s must be positive")
        if self.max_consecutive_failures <= 0:
            raise ValueError("max_consecutive_failures must be positive")
        if self.max_exploration_s <= 0.0:
            raise ValueError("max_exploration_s must be positive")
        if self.blacklist_ttl_s < 0.0:
            raise ValueError("blacklist_ttl_s must not be negative")
        if self.save_timeout_s <= 0.0:
            raise ValueError("save_timeout_s must be positive")
        if self.sensor_recovery_timeout_s <= 0.0:
            raise ValueError("sensor_recovery_timeout_s must be positive")


@dataclass(frozen=True)
class Decision:
    state: ExplorationState
    cancel_goal: bool = False
    publish_stop: bool = False
    save_map: bool = False
    reason: str = ""


class ExplorationController:
    """외부 이벤트를 탐색 상태와 안전 동작 요청으로 변환합니다."""

    def __init__(self, policy: ExplorationPolicy):
        self.policy = policy
        self.state = ExplorationState.WAITING_FOR_SYSTEM
        self.consecutive_failures = 0
        self._started_at = None
        self._goal_started_at = None
        self._current_goal = None
        self._completion_started_at = None
        self._save_started_at = None
        self._sensor_stale_started_at = None
        self._blocked = []

    @property
    def accepts_new_goal(self) -> bool:
        """늦게 도착한 비동기 응답이 새 이동을 시작해도 되는 상태입니다."""
        return self.state is ExplorationState.SELECTING_FRONTIER

    def mark_ready(self, now: float) -> Decision:
        if self.state is ExplorationState.WAITING_FOR_SYSTEM:
            self._started_at = now
            self.state = ExplorationState.SELECTING_FRONTIER
        return self._decision()

    def goal_selected(self, now: float, point: Point) -> Decision:
        if self.state not in {
            ExplorationState.SELECTING_FRONTIER,
            ExplorationState.RECOVERY,
        }:
            raise RuntimeError(f"cannot select a goal while {self.state.name}")
        self._completion_started_at = None
        self._goal_started_at = now
        self._current_goal = point
        self.state = ExplorationState.NAVIGATING
        return self._decision()

    def goal_succeeded(self, now: float) -> Decision:
        if self.state is not ExplorationState.NAVIGATING:
            raise RuntimeError(f"no active goal while {self.state.name}")
        self._clear_goal()
        self.consecutive_failures = 0
        self.state = ExplorationState.SELECTING_FRONTIER
        return self._decision()

    def goal_obsolete(self, now: float) -> Decision:
        """지도 갱신으로 사라진 목표를 실패로 세지 않고 다시 선택합니다."""
        if self.state is not ExplorationState.NAVIGATING:
            raise RuntimeError(f"no active goal while {self.state.name}")
        self._clear_goal()
        self.state = ExplorationState.SELECTING_FRONTIER
        return self._decision(
            cancel_goal=True,
            publish_stop=True,
            reason="frontier disappeared while navigating",
        )

    def goal_failed(self, now: float, reason: str) -> Decision:
        return self._record_goal_failure(now, reason, cancel_goal=False)

    def system_failed(self, reason: str) -> Decision:
        """Stop exploration for an infrastructure failure, not a bad goal."""
        if self.state in {ExplorationState.COMPLETED, ExplorationState.FAILED}:
            return self._decision(reason=reason)
        return self._fail(reason, cancel_goal=True)

    def candidate_unreachable(
        self, now: float, point: Point, reason: str
    ) -> Decision:
        """경로가 없는 후보를 TTL 동안 제외하고 다음 후보를 고릅니다."""
        if not self.accepts_new_goal:
            raise RuntimeError(
                f"cannot reject a candidate while {self.state.name}"
            )
        self._blocked.append((point, now + self.policy.blacklist_ttl_s))
        return self._decision(reason=reason)

    def frontiers_missing(self, now: float) -> Decision:
        if self.state in {
            ExplorationState.SELECTING_FRONTIER,
            ExplorationState.RECOVERY,
        }:
            self._completion_started_at = now
            self.state = ExplorationState.VERIFYING_COMPLETE
        return self._decision()

    def frontiers_found(self, now: float) -> Decision:
        if self.state is ExplorationState.VERIFYING_COMPLETE:
            self._completion_started_at = None
            self.state = ExplorationState.SELECTING_FRONTIER
        return self._decision()

    def tick(self, now: float, sensors_fresh: bool) -> Decision:
        if self.state in {ExplorationState.COMPLETED, ExplorationState.FAILED}:
            return self._decision()
        if self.state is ExplorationState.SAVING_MAP:
            if (
                self._save_started_at is not None
                and now - self._save_started_at >= self.policy.save_timeout_s
            ):
                return self._fail("map save timed out", cancel_goal=False)
            return self._decision()
        if self.state is ExplorationState.WAITING_FOR_SYSTEM:
            return self._decision()

        if not sensors_fresh:
            if self.state is ExplorationState.WAITING_FOR_SENSORS:
                if (
                    self._sensor_stale_started_at is not None
                    and now - self._sensor_stale_started_at
                    >= self.policy.sensor_recovery_timeout_s
                ):
                    return self._fail(
                        "sensor recovery timed out", cancel_goal=False
                    )
                return self._decision(
                    publish_stop=True,
                )
            self._clear_goal()
            self._completion_started_at = None
            self._sensor_stale_started_at = now
            self.state = ExplorationState.WAITING_FOR_SENSORS
            return self._decision(
                cancel_goal=True,
                publish_stop=True,
                reason="sensor data became stale",
            )

        if self.state is ExplorationState.WAITING_FOR_SENSORS:
            self._sensor_stale_started_at = None
            self.state = ExplorationState.SELECTING_FRONTIER
            return self._decision(reason="sensor data recovered")

        if (
            self._started_at is not None
            and now - self._started_at >= self.policy.max_exploration_s
        ):
            return self._fail(
                "maximum exploration time exceeded", cancel_goal=True
            )

        if (
            self.state is ExplorationState.NAVIGATING
            and self._goal_started_at is not None
            and now - self._goal_started_at >= self.policy.goal_timeout_s
        ):
            return self._record_goal_failure(
                now,
                "navigation goal timed out",
                cancel_goal=True,
                publish_stop=True,
            )

        if self.state is ExplorationState.RECOVERY:
            self.state = ExplorationState.SELECTING_FRONTIER
            return self._decision()

        if (
            self.state is ExplorationState.VERIFYING_COMPLETE
            and self._completion_started_at is not None
            and now - self._completion_started_at
            >= self.policy.completion_hold_s
        ):
            self.state = ExplorationState.SAVING_MAP
            self._save_started_at = now
            return self._decision(
                cancel_goal=True,
                publish_stop=True,
                save_map=True,
                reason="no frontier remained during completion hold",
            )
        return self._decision()

    def mark_save_finished(self, success: bool) -> Decision:
        if self.state is not ExplorationState.SAVING_MAP:
            raise RuntimeError(f"not saving a map while {self.state.name}")
        if success:
            self._save_started_at = None
            self.state = ExplorationState.COMPLETED
            return self._decision(reason="map save completed")
        return self._fail("map save failed", cancel_goal=False)

    def blocked_points(self, now: float) -> tuple[Point, ...]:
        self._blocked = [entry for entry in self._blocked if entry[1] > now]
        return tuple(point for point, _expires_at in self._blocked)

    def _record_goal_failure(
        self,
        now: float,
        reason: str,
        cancel_goal: bool = False,
        publish_stop: bool = False,
    ) -> Decision:
        if self.state is not ExplorationState.NAVIGATING:
            raise RuntimeError(f"no active goal while {self.state.name}")
        if self._current_goal is not None:
            self._blocked.append(
                (self._current_goal, now + self.policy.blacklist_ttl_s)
            )
        self._clear_goal()
        self.consecutive_failures += 1
        if self.consecutive_failures >= self.policy.max_consecutive_failures:
            return self._fail(
                f"maximum consecutive failures reached: {reason}",
                cancel_goal=cancel_goal,
            )
        self.state = ExplorationState.RECOVERY
        return self._decision(
            cancel_goal=cancel_goal,
            publish_stop=publish_stop,
            reason=reason,
        )

    def _clear_goal(self):
        self._goal_started_at = None
        self._current_goal = None

    def _fail(self, reason: str, cancel_goal: bool) -> Decision:
        self._clear_goal()
        self._sensor_stale_started_at = None
        self.state = ExplorationState.FAILED
        return self._decision(
            cancel_goal=cancel_goal,
            publish_stop=True,
            reason=reason,
        )

    def _decision(
        self,
        cancel_goal: bool = False,
        publish_stop: bool = False,
        save_map: bool = False,
        reason: str = "",
    ) -> Decision:
        return Decision(
            state=self.state,
            cancel_goal=cancel_goal,
            publish_stop=publish_stop,
            save_map=save_map,
            reason=reason,
        )
