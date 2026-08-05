"""ROS가 없는 CI에서 탐색 노드의 안전 연결 계약을 검증합니다."""

import ast
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
NODE = (
    REPO
    / "src"
    / "potner_exploration"
    / "potner_exploration"
    / "exploration_node.py"
)
TREE = ast.parse(NODE.read_text(encoding="utf-8"))


def method_named(name):
    return next(
        node
        for node in ast.walk(TREE)
        if isinstance(node, ast.FunctionDef) and node.name == name
    )


def called_names(method):
    result = []
    for node in ast.walk(method):
        if not isinstance(node, ast.Call):
            continue
        if isinstance(node.func, ast.Name):
            result.append(node.func.id)
        elif isinstance(node.func, ast.Attribute):
            result.append(node.func.attr)
    return result


def loaded_attributes(method):
    return {
        node.attr
        for node in ast.walk(method)
        if isinstance(node, ast.Attribute) and isinstance(node.ctx, ast.Load)
    }


def test_sensor_status_uses_diagnostic_freshness_evaluator():
    assert "evaluate_freshness" in called_names(method_named("_sensor_status"))


def test_navigation_revalidation_is_guarded_by_new_map_revision():
    timer = method_named("_on_timer")

    assert "needs_check" in loaded_attributes(timer)
    assert "_navigation_target_remains_frontier" in called_names(timer)


def test_new_navigation_goal_requires_current_map_revalidation():
    calls = called_names(method_named("_send_navigation_goal"))

    assert "reset" in calls
    assert "mark_checked" not in calls


def test_navigation_target_check_uses_bounded_local_map_work():
    calls = called_names(method_named("_navigation_target_remains_frontier"))

    assert "target_remains_local_frontier" in calls
    assert "_current_frontier_analysis" not in calls


def test_frontier_selection_polls_background_analysis_worker():
    calls = called_names(method_named("_start_frontier_selection"))

    assert "_poll_frontier_analysis" in calls
    assert "_current_frontiers" not in calls


def test_background_analysis_uses_revision_aware_proposal_policy():
    calls = called_names(method_named("_poll_frontier_analysis"))

    assert "accepts_analysis" in calls


def test_shutdown_stops_navigation_before_waiting_for_worker():
    calls = called_names(method_named("destroy_node"))

    assert calls.index("_cancel_path_check") < calls.index("shutdown")
    assert calls.index("_cancel_navigation") < calls.index("shutdown")
    assert calls.index("_publish_stop") < calls.index("shutdown")
