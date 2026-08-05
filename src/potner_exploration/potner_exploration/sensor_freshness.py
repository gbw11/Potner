"""ROS와 독립적으로 센서 데이터의 최신 상태를 판정합니다."""

from dataclasses import dataclass


@dataclass(frozen=True)
class FreshnessLimits:
    map_timeout_s: float
    odom_timeout_s: float
    tf_timeout_s: float
    future_tolerance_s: float = 0.5

    def __post_init__(self):
        for name in ("map_timeout_s", "odom_timeout_s", "tf_timeout_s"):
            if getattr(self, name) <= 0.0:
                raise ValueError(f"{name} must be positive")
        if self.future_tolerance_s < 0.0:
            raise ValueError("future_tolerance_s must not be negative")


@dataclass(frozen=True)
class FreshnessStatus:
    fresh: bool
    map_age_s: float | None
    odom_age_s: float | None
    tf_age_s: float | None
    stale_sources: tuple[str, ...]
    _descriptions: tuple[str, ...]

    def describe(self) -> str:
        if self.fresh:
            return "sensor data fresh"
        return "sensor stale: " + "; ".join(self._descriptions)


def evaluate_freshness(
    now: float,
    last_map_at: float | None,
    last_odom_at: float | None,
    last_tf_at: float | None,
    tf_ready: bool,
    limits: FreshnessLimits,
) -> FreshnessStatus:
    ages = {
        "map": _age(now, last_map_at),
        "odom": _age(now, last_odom_at),
        "tf": _age(now, last_tf_at),
    }
    timeouts = {
        "map": limits.map_timeout_s,
        "odom": limits.odom_timeout_s,
        "tf": limits.tf_timeout_s,
    }
    stale_sources = []
    descriptions = []

    for source in ("map", "odom", "tf"):
        age = ages[source]
        if source == "tf" and not tf_ready:
            stale_sources.append(source)
            descriptions.append("tf(lookup unavailable)")
        elif age is None:
            stale_sources.append(source)
            descriptions.append(f"{source}(missing)")
        elif age < -limits.future_tolerance_s:
            stale_sources.append(source)
            descriptions.append(f"{source}(future timestamp age={age:.2f}s)")
        elif age > timeouts[source]:
            stale_sources.append(source)
            descriptions.append(
                f"{source}(age={age:.2f}s limit={timeouts[source]:.2f}s)"
            )

    return FreshnessStatus(
        fresh=not stale_sources,
        map_age_s=ages["map"],
        odom_age_s=ages["odom"],
        tf_age_s=ages["tf"],
        stale_sources=tuple(stale_sources),
        _descriptions=tuple(descriptions),
    )


def _age(now: float, stamp: float | None) -> float | None:
    return None if stamp is None else now - stamp
