import pytest

from potner_exploration.sensor_freshness import (
    FreshnessLimits,
    evaluate_freshness,
)


LIMITS = FreshnessLimits(
    map_timeout_s=6.0,
    odom_timeout_s=2.0,
    tf_timeout_s=1.0,
)


def evaluate(**overrides):
    values = {
        "now": 10.0,
        "last_map_at": 9.0,
        "last_odom_at": 9.5,
        "last_tf_at": 9.8,
        "tf_ready": True,
        "limits": LIMITS,
    }
    values.update(overrides)
    return evaluate_freshness(**values)


def test_recent_sensor_data_is_fresh():
    status = evaluate()

    assert status.fresh
    assert status.stale_sources == ()
    assert status.describe() == "sensor data fresh"


def test_odom_staleness_reports_measured_age_and_limit():
    status = evaluate(last_odom_at=7.9)

    assert not status.fresh
    assert status.stale_sources == ("odom",)
    assert status.odom_age_s == pytest.approx(2.1)
    assert "odom(age=2.10s limit=2.00s)" in status.describe()


def test_missing_map_is_identified_separately():
    status = evaluate(last_map_at=None)

    assert status.stale_sources == ("map",)
    assert status.map_age_s is None
    assert "map(missing)" in status.describe()


def test_failed_tf_lookup_is_identified_separately():
    status = evaluate(tf_ready=False)

    assert status.stale_sources == ("tf",)
    assert "tf(lookup unavailable)" in status.describe()


def test_stale_tf_reports_measured_age_and_limit():
    status = evaluate(last_tf_at=8.9)

    assert status.stale_sources == ("tf",)
    assert status.tf_age_s == pytest.approx(1.1)
    assert "tf(age=1.10s limit=1.00s)" in status.describe()


@pytest.mark.parametrize(
    ("field", "source"),
    (("last_map_at", "map"), ("last_odom_at", "odom"), ("last_tf_at", "tf")),
)
def test_timestamps_over_half_a_second_in_the_future_are_rejected(
    field, source
):
    status = evaluate(**{field: 10.6})

    assert status.stale_sources == (source,)
    assert "future timestamp" in status.describe()


def test_freshness_limits_must_be_positive():
    with pytest.raises(ValueError, match="odom_timeout_s"):
        FreshnessLimits(6.0, 0.0, 1.0)
