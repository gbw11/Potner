"""자율 SLAM의 TF와 주행 명령 경로 계약을 검증합니다.

CI에는 ROS 2가 없으므로 launch와 노드를 실행할 수 없습니다. 대신 저장소가
소유하는 설정과 소스의 연결 지점을 함께 검사해, base_link 중복 부모와
twist_mux 우회가 다시 생기지 않게 합니다.
"""

from pathlib import Path
import xml.etree.ElementTree as ET

import yaml


REPO = Path(__file__).resolve().parent.parent
BASE_DRIVER = (
    REPO / "src" / "potner_base" / "potner_base" / "base_driver_node.py"
)
PARAMS = REPO / "src" / "potner_bringup" / "config" / "potner_params.yaml"
NAV2_LAUNCH = REPO / "src" / "potner_bringup" / "launch" / "nav2.launch.py"
EXPLORATION_ROOT = REPO / "src" / "potner_exploration"
BRINGUP = REPO / "src" / "potner_bringup"
EXPLORATION_LAUNCH = BRINGUP / "launch" / "exploration.launch.py"
NAVIGATION_CORE_LAUNCH = BRINGUP / "launch" / "navigation_core.launch.py"
EXPLORATION_CONFIG = BRINGUP / "config" / "exploration.yaml"
NAV2_CONFIG = BRINGUP / "config" / "nav2_params.yaml"
TWIST_MUX_CONFIG = BRINGUP / "config" / "twist_mux.yaml"
ROBOT_LAUNCH = BRINGUP / "launch" / "robot.launch.py"


def test_동적_오도메트리_TF는_base_footprint를_자식으로_쓴다():
    params = yaml.safe_load(PARAMS.read_text(encoding="utf-8"))
    base_params = params["base_driver"]["ros__parameters"]
    source = BASE_DRIVER.read_text(encoding="utf-8")

    assert base_params["base_frame"] == "base_footprint"
    assert 'declare_parameter("base_frame", "base_footprint")' in source
    assert 'msg.child_frame_id = self.base_frame' in source
    assert 'tf.child_frame_id = self.base_frame' in source


def test_저장_지도_Nav2_속도는_twist_mux_입력으로_보낸다():
    source = NAV2_LAUNCH.read_text(encoding="utf-8")

    assert "navigation_core.launch.py" in source


def test_Nav2_controller만_cmd_vel_nav를_발행하고_smoother는_띄우지_않는다():
    source = NAVIGATION_CORE_LAUNCH.read_text(encoding="utf-8")

    assert source.count('remappings=[("cmd_vel", "cmd_vel_nav")]') == 2
    assert "nav2_velocity_smoother" not in source


def test_탐색_패키지는_실행에_필요한_ROS_의존성을_선언한다():
    root = ET.parse(EXPLORATION_ROOT / "package.xml").getroot()
    dependencies = {node.text for node in root.findall("exec_depend")}

    assert {
        "rclpy",
        "geometry_msgs",
        "nav_msgs",
        "nav2_msgs",
        "slam_toolbox",
    } <= dependencies


def test_탐색_노드를_console_script로_설치한다():
    source = (EXPLORATION_ROOT / "setup.py").read_text(encoding="utf-8")

    assert "exploration = potner_exploration.exploration_node:main" in source


def test_탐색_설정은_안전한_양수_제한값을_사용한다():
    params = yaml.safe_load(EXPLORATION_CONFIG.read_text(encoding="utf-8"))
    values = params["exploration"]["ros__parameters"]

    assert values["min_cluster_size"] > 0
    assert values["clearance_m"] >= 0.14
    assert values["goal_timeout_s"] > 0.0
    assert values["completion_hold_s"] > 0.0
    assert values["max_exploration_s"] > values["goal_timeout_s"]
    assert values["map_timeout_s"] > 2.0
    assert values["odom_timeout_s"] > 0.0
    assert values["path_check_timeout_s"] > 0.0
    assert values["navigation_accept_timeout_s"] > 0.0
    assert values["frontier_replan_distance_m"] > 0.0
    assert values["tf_timeout_s"] > 0.0
    assert values["sensor_recovery_timeout_s"] > max(
        values["map_timeout_s"],
        values["odom_timeout_s"],
        values["tf_timeout_s"],
    )
    assert values["sensor_stop_publish_period_s"] > 0.0
    assert values["save_timeout_s"] > 0.0


def test_Nav2는_저속_차동구동과_미지공간_계획_plugin을_설정한다():
    params = yaml.safe_load(NAV2_CONFIG.read_text(encoding="utf-8"))
    controller = params["controller_server"]["ros__parameters"]
    planner = params["planner_server"]["ros__parameters"]
    local = params["local_costmap"]["local_costmap"]["ros__parameters"]
    global_map = params["global_costmap"]["global_costmap"]["ros__parameters"]

    assert controller["controller_plugins"] == ["FollowPath"]
    assert controller["FollowPath"]["plugin"] == "dwb_core::DWBLocalPlanner"
    assert controller["FollowPath"]["max_vel_x"] <= 0.12
    assert controller["FollowPath"]["max_vel_theta"] <= 0.5
    assert planner["GridBased"]["plugin"] == "nav2_navfn_planner/NavfnPlanner"
    assert planner["GridBased"]["allow_unknown"] is True
    assert "obstacle_layer" in local["plugins"]
    assert "static_layer" in global_map["plugins"]
    assert local["inflation_layer"]["inflation_radius"] >= 0.20
    assert global_map["inflation_layer"]["inflation_radius"] >= 0.20


def test_통합_탐색_launch는_AMCL없이_SLAM_Nav2_저장을_구성한다():
    source = EXPLORATION_LAUNCH.read_text(encoding="utf-8")

    assert "robot.launch.py" in source
    assert "async_slam_toolbox_node" in source
    assert "navigation_core.launch.py" in source
    assert "map_saver_server" in source
    assert "lifecycle_manager_map_saver" in source
    assert 'package="potner_exploration"' in source
    assert "localization_launch.py" not in source
    assert "amcl" not in source.lower()


def test_twist_mux_output_must_pass_through_directional_safety():
    mux = yaml.safe_load(TWIST_MUX_CONFIG.read_text(encoding="utf-8"))
    topics = mux["twist_mux"]["ros__parameters"]["topics"]
    launch = ROBOT_LAUNCH.read_text(encoding="utf-8")

    assert "safety" not in topics
    assert 'remappings=[("cmd_vel_out", "cmd_vel_requested")]' in launch
    assert 'remappings=[("cmd_vel_out", "cmd_vel")]' not in launch


def test_nav2_keeps_backup_and_spin_recovery_with_early_obstacle_inflation():
    params = yaml.safe_load(NAV2_CONFIG.read_text(encoding="utf-8"))
    behaviors = params["behavior_server"]["ros__parameters"]
    local = params["local_costmap"]["local_costmap"]["ros__parameters"]
    global_map = params["global_costmap"]["global_costmap"]["ros__parameters"]

    assert {"backup", "spin"} <= set(behaviors["behavior_plugins"])
    assert local["inflation_layer"]["inflation_radius"] == 0.30
    assert global_map["inflation_layer"]["inflation_radius"] == 0.30


def test_all_configured_motion_limits_are_reduced_by_thirty_percent():
    nav2 = yaml.safe_load(NAV2_CONFIG.read_text(encoding="utf-8"))
    potner = yaml.safe_load(PARAMS.read_text(encoding="utf-8"))
    follow = nav2["controller_server"]["ros__parameters"]["FollowPath"]
    behavior = nav2["behavior_server"]["ros__parameters"]

    assert follow["max_vel_x"] == 0.084
    assert follow["max_speed_xy"] == 0.084
    assert follow["max_vel_theta"] == 0.35
    assert follow["acc_lim_x"] == 0.175
    assert follow["decel_lim_x"] == -0.175
    assert follow["acc_lim_theta"] == 0.56
    assert follow["decel_lim_theta"] == -0.56
    assert behavior["max_rotational_vel"] == 0.35
    assert behavior["min_rotational_vel"] == 0.07
    assert behavior["rotational_acc_lim"] == 0.56
    assert potner["base_driver"]["ros__parameters"]["max_wheel_speed"] == 0.175
    docking = potner["docking_server"]["ros__parameters"]
    assert docking["approach_speed"] == 0.084
    assert docking["max_angular"] == 0.56
