"""ROS에 의존하지 않는 방향별 주행 안전 정책."""

import math
from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class SafetyConfig:
    stop_distance: float = 0.25
    bumper_stop_distance: float = 0.12
    clear_hysteresis: float = 0.08
    min_valid_samples: int = 1

    def __post_init__(self):
        if self.stop_distance <= 0.0:
            raise ValueError("stop_distance must be positive")
        if self.bumper_stop_distance <= 0.0:
            raise ValueError("bumper_stop_distance must be positive")
        if self.clear_hysteresis < 0.0:
            raise ValueError("clear_hysteresis must not be negative")
        if self.min_valid_samples <= 0:
            raise ValueError("min_valid_samples must be positive")


@dataclass(frozen=True)
class SectorClearance:
    front: float = math.inf
    rear: float = math.inf
    left: float = math.inf
    right: float = math.inf
    front_samples: int = 0
    rear_samples: int = 0
    left_samples: int = 0
    right_samples: int = 0


@dataclass(frozen=True)
class SafetyResult:
    linear_x: float
    angular_z: float
    limited: bool
    reasons: tuple[str, ...]


def inputs_fresh(
    now: float,
    scan_stamp: float,
    command_stamp: float,
    scan_timeout: float,
    command_timeout: float,
) -> bool:
    """최신 scan과 선택 속도 명령이 모두 제한 시간 안인지 반환합니다."""
    if scan_timeout <= 0.0 or command_timeout <= 0.0:
        raise ValueError("input timeouts must be positive")
    if scan_stamp is None or command_stamp is None:
        return False
    scan_age = now - scan_stamp
    command_age = now - command_stamp
    return (
        0.0 <= scan_age <= scan_timeout
        and 0.0 <= command_age <= command_timeout
    )


def observe_scan(
    ranges: Iterable[float],
    angle_min: float,
    angle_increment: float,
    range_min: float,
    range_max: float,
    front_arc_deg: float,
    rear_arc_deg: float,
    side_arc_deg: float,
) -> SectorClearance:
    """LaserScan 표본을 전후좌우 최근접 거리와 유효 표본 수로 요약합니다."""
    half_arcs = {
        "front": math.radians(front_arc_deg) / 2.0,
        "rear": math.radians(rear_arc_deg) / 2.0,
        "left": math.radians(side_arc_deg) / 2.0,
        "right": math.radians(side_arc_deg) / 2.0,
    }
    centers = {
        "front": 0.0,
        "rear": math.pi,
        "left": math.pi / 2.0,
        "right": -math.pi / 2.0,
    }
    nearest = {name: math.inf for name in centers}
    samples = {name: 0 for name in centers}

    for index, distance in enumerate(ranges):
        if not math.isfinite(distance) or not range_min < distance < range_max:
            continue
        angle = angle_min + index * angle_increment
        for name, center in centers.items():
            if abs(_normalize_angle(angle - center)) <= half_arcs[name]:
                nearest[name] = min(nearest[name], distance)
                samples[name] += 1

    return SectorClearance(
        front=nearest["front"],
        rear=nearest["rear"],
        left=nearest["left"],
        right=nearest["right"],
        front_samples=samples["front"],
        rear_samples=samples["rear"],
        left_samples=samples["left"],
        right_samples=samples["right"],
    )


class DirectionalSafety:
    """위험한 방향의 선속도·각속도 성분만 제거합니다."""

    def __init__(self, config: SafetyConfig = None):
        self.config = config or SafetyConfig()
        self._blocked = {
            "front": True,
            "rear": True,
            "left": True,
            "right": True,
        }
        self._observed = {name: False for name in self._blocked}
        self._bumper_blocked = {"left": False, "right": False}

    def update_scan(self, clearance: SectorClearance):
        for name in self._blocked:
            distance = getattr(clearance, name)
            samples = getattr(clearance, f"{name}_samples")
            observed = samples >= self.config.min_valid_samples
            self._observed[name] = observed
            self._blocked[name] = self._update_blocked(
                self._blocked[name], distance, self.config.stop_distance, observed
            )

    def update_bumper(self, side: str, distance: float):
        if side not in self._bumper_blocked:
            raise ValueError(f"unknown bumper side: {side}")
        self._bumper_blocked[side] = self._update_blocked(
            self._bumper_blocked[side],
            distance,
            self.config.bumper_stop_distance,
            math.isfinite(distance) and distance >= 0.0,
        )

    def filter(self, linear_x: float, angular_z: float) -> SafetyResult:
        filtered_linear = linear_x
        filtered_angular = angular_z
        reasons = []

        if linear_x > 0.0:
            if self._blocked["front"]:
                filtered_linear = 0.0
                reasons.append(self._sector_reason("front"))
            if any(self._bumper_blocked.values()):
                filtered_linear = 0.0
                reasons.append("front_tof")
        elif linear_x < 0.0 and self._blocked["rear"]:
            filtered_linear = 0.0
            reasons.append(self._sector_reason("rear"))

        if angular_z > 0.0:
            if self._blocked["left"]:
                filtered_angular = 0.0
                reasons.append(self._sector_reason("left"))
            if self._bumper_blocked["left"]:
                filtered_angular = 0.0
                reasons.append("left_tof")
        elif angular_z < 0.0:
            if self._blocked["right"]:
                filtered_angular = 0.0
                reasons.append(self._sector_reason("right"))
            if self._bumper_blocked["right"]:
                filtered_angular = 0.0
                reasons.append("right_tof")

        unique_reasons = tuple(dict.fromkeys(reasons))
        return SafetyResult(
            linear_x=filtered_linear,
            angular_z=filtered_angular,
            limited=(
                filtered_linear != linear_x or filtered_angular != angular_z
            ),
            reasons=unique_reasons,
        )

    def _sector_reason(self, sector: str) -> str:
        if self._observed[sector]:
            return sector
        return f"{sector}_unobserved"

    def _update_blocked(
        self,
        currently_blocked: bool,
        distance: float,
        limit: float,
        observed: bool,
    ) -> bool:
        if not observed:
            return True
        if currently_blocked:
            return distance <= limit + self.config.clear_hysteresis
        return distance <= limit


def _normalize_angle(angle: float) -> float:
    return math.atan2(math.sin(angle), math.cos(angle))
