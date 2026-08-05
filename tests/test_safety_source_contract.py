"""최종 모터 명령이 방향별 safety 필터를 우회하지 않는지 검증합니다."""

from pathlib import Path

import yaml


REPO = Path(__file__).resolve().parent.parent
SAFETY_NODE = (
    REPO / "src" / "potner_mission" / "potner_mission" / "safety_node.py"
)
PARAMS = REPO / "src" / "potner_bringup" / "config" / "potner_params.yaml"


def test_safety_node_consumes_selected_command_and_owns_final_cmd_vel():
    source = SAFETY_NODE.read_text(encoding="utf-8")

    assert 'Twist, "cmd_vel_requested"' in source
    assert 'create_publisher(Twist, "cmd_vel", 10)' in source
    assert "cmd_vel_safety" not in source


def test_safety_node_has_scan_and_command_watchdogs():
    source = SAFETY_NODE.read_text(encoding="utf-8")
    params = yaml.safe_load(PARAMS.read_text(encoding="utf-8"))
    safety = params["safety"]["ros__parameters"]

    assert "inputs_fresh(" in source
    assert safety["scan_timeout"] == 0.5
    assert safety["command_timeout"] == 0.5
    assert safety["rear_arc_deg"] == 60.0
    assert safety["side_arc_deg"] == 120.0
