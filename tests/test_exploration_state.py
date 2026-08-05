"""자율 탐색 상태 전이와 안전 종료 정책을 검증합니다."""

from potner_exploration.exploration_state import (
    ExplorationController,
    ExplorationPolicy,
    ExplorationState,
)


def test_준비_목표선택_성공은_다음_frontier_선택으로_이어진다():
    controller = ExplorationController(ExplorationPolicy())

    assert not controller.accepts_new_goal
    assert (
        controller.mark_ready(0.0).state
        is ExplorationState.SELECTING_FRONTIER
    )
    assert controller.accepts_new_goal
    assert (
        controller.goal_selected(1.0, (2.0, 3.0)).state
        is ExplorationState.NAVIGATING
    )
    assert not controller.accepts_new_goal
    assert (
        controller.goal_succeeded(2.0).state
        is ExplorationState.SELECTING_FRONTIER
    )
    assert controller.accepts_new_goal
    assert controller.consecutive_failures == 0


def test_frontier_부재가_확인시간_동안_이어져야_저장을_요청한다():
    controller = ExplorationController(
        ExplorationPolicy(completion_hold_s=10.0)
    )
    controller.mark_ready(0.0)

    decision = controller.frontiers_missing(1.0)
    assert decision.state is ExplorationState.VERIFYING_COMPLETE
    assert not decision.save_map
    assert (
        controller.tick(10.9, sensors_fresh=True).state
        is ExplorationState.VERIFYING_COMPLETE
    )

    decision = controller.tick(11.0, sensors_fresh=True)
    assert decision.state is ExplorationState.SAVING_MAP
    assert decision.cancel_goal
    assert decision.publish_stop
    assert decision.save_map


def test_완료_확인_중_frontier가_생기면_탐색을_재개한다():
    controller = ExplorationController(
        ExplorationPolicy(completion_hold_s=5.0)
    )
    controller.mark_ready(0.0)
    controller.frontiers_missing(1.0)

    decision = controller.frontiers_found(2.0)

    assert decision.state is ExplorationState.SELECTING_FRONTIER
    assert (
        controller.tick(10.0, sensors_fresh=True).state
        is ExplorationState.SELECTING_FRONTIER
    )


def test_목표_시간초과는_취소와_정지를_요청하고_실패지점을_차단한다():
    controller = ExplorationController(
        ExplorationPolicy(goal_timeout_s=5.0, blacklist_ttl_s=20.0)
    )
    controller.mark_ready(0.0)
    controller.goal_selected(1.0, (2.0, 3.0))

    decision = controller.tick(6.0, sensors_fresh=True)

    assert decision.state is ExplorationState.RECOVERY
    assert decision.cancel_goal
    assert decision.publish_stop
    assert controller.blocked_points(6.0) == ((2.0, 3.0),)
    assert controller.blocked_points(26.0) == ()


def test_경로가_없는_frontier는_실패횟수없이_일시_차단한다():
    controller = ExplorationController(
        ExplorationPolicy(blacklist_ttl_s=20.0)
    )
    controller.mark_ready(0.0)

    decision = controller.candidate_unreachable(1.0, (4.0, 5.0), "no path")

    assert decision.state is ExplorationState.SELECTING_FRONTIER
    assert controller.consecutive_failures == 0
    assert controller.blocked_points(1.0) == ((4.0, 5.0),)
    assert controller.blocked_points(21.0) == ()


def test_이동중_frontier가_사라지면_실패로_세지_않고_재선택한다():
    controller = ExplorationController(ExplorationPolicy())
    controller.mark_ready(0.0)
    controller.goal_selected(1.0, (2.0, 3.0))

    decision = controller.goal_obsolete(2.0)

    assert decision.state is ExplorationState.SELECTING_FRONTIER
    assert decision.cancel_goal
    assert decision.publish_stop
    assert controller.consecutive_failures == 0
    assert controller.blocked_points(2.0) == ()


def test_센서가_오래되면_안전하게_멈추고_복구를_기다린다():
    controller = ExplorationController(
        ExplorationPolicy(sensor_recovery_timeout_s=10.0)
    )
    controller.mark_ready(0.0)
    controller.goal_selected(1.0, (1.0, 0.0))

    decision = controller.tick(2.0, sensors_fresh=False)

    assert decision.state is ExplorationState.WAITING_FOR_SENSORS
    assert decision.cancel_goal
    assert decision.publish_stop
    assert "sensor" in decision.reason


def test_센서가_제한시간_안에_복구되면_새_frontier를_선택한다():
    controller = ExplorationController(
        ExplorationPolicy(sensor_recovery_timeout_s=10.0)
    )
    controller.mark_ready(0.0)
    controller.goal_selected(1.0, (1.0, 0.0))
    controller.tick(2.0, sensors_fresh=False)

    decision = controller.tick(5.0, sensors_fresh=True)

    assert decision.state is ExplorationState.SELECTING_FRONTIER
    assert controller.accepts_new_goal
    assert not decision.cancel_goal


def test_센서가_계속_오래되면_복구_제한시간_후_실패한다():
    controller = ExplorationController(
        ExplorationPolicy(sensor_recovery_timeout_s=10.0)
    )
    controller.mark_ready(0.0)
    controller.tick(2.0, sensors_fresh=False)

    waiting = controller.tick(11.9, sensors_fresh=False)
    failed = controller.tick(12.0, sensors_fresh=False)

    assert waiting.state is ExplorationState.WAITING_FOR_SENSORS
    assert waiting.publish_stop
    assert failed.state is ExplorationState.FAILED
    assert failed.publish_stop
    assert "sensor recovery timed out" in failed.reason


def test_연속_실패_한도에_도달하면_탐색을_종료한다():
    controller = ExplorationController(
        ExplorationPolicy(max_consecutive_failures=2)
    )
    controller.mark_ready(0.0)
    controller.goal_selected(1.0, (1.0, 0.0))
    first = controller.goal_failed(2.0, "blocked")
    assert first.state is ExplorationState.RECOVERY

    controller.tick(2.1, sensors_fresh=True)
    controller.goal_selected(3.0, (2.0, 0.0))
    second = controller.goal_failed(4.0, "blocked")

    assert second.state is ExplorationState.FAILED
    assert second.publish_stop


def test_전체_탐색시간을_넘으면_현재_목표를_취소한다():
    controller = ExplorationController(
        ExplorationPolicy(max_exploration_s=30.0)
    )
    controller.mark_ready(5.0)
    controller.goal_selected(6.0, (1.0, 0.0))

    decision = controller.tick(35.0, sensors_fresh=True)

    assert decision.state is ExplorationState.FAILED
    assert decision.cancel_goal
    assert decision.publish_stop
    assert "maximum exploration time" in decision.reason


def test_지도_저장_성공과_실패를_최종상태로_변환한다():
    success = ExplorationController(ExplorationPolicy(completion_hold_s=1.0))
    success.mark_ready(0.0)
    success.frontiers_missing(1.0)
    success.tick(2.0, sensors_fresh=True)
    assert success.mark_save_finished(True).state is ExplorationState.COMPLETED

    failure = ExplorationController(ExplorationPolicy(completion_hold_s=1.0))
    failure.mark_ready(0.0)
    failure.frontiers_missing(1.0)
    failure.tick(2.0, sensors_fresh=True)
    decision = failure.mark_save_finished(False)
    assert decision.state is ExplorationState.FAILED
    assert decision.publish_stop
    assert "map save" in decision.reason


def test_지도_저장_응답이_없으면_제한시간_후_실패한다():
    controller = ExplorationController(
        ExplorationPolicy(completion_hold_s=1.0, save_timeout_s=5.0)
    )
    controller.mark_ready(0.0)
    controller.frontiers_missing(1.0)
    assert (
        controller.tick(2.0, sensors_fresh=True).state
        is ExplorationState.SAVING_MAP
    )

    decision = controller.tick(7.0, sensors_fresh=True)

    assert decision.state is ExplorationState.FAILED
    assert decision.publish_stop
    assert "map save timed out" in decision.reason


def test_system_failure_stops_without_treating_a_frontier_as_unreachable():
    controller = ExplorationController(ExplorationPolicy())
    controller.mark_ready(0.0)

    decision = controller.system_failed("planner server unavailable")

    assert decision.state is ExplorationState.FAILED
    assert decision.cancel_goal
    assert decision.publish_stop
    assert controller.consecutive_failures == 0
    assert controller.blocked_points(1.0) == ()
