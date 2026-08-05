"""frontier를 Nav2 목표로 보내 미지 공간을 자동 탐색하는 ROS 2 노드."""

import math
from datetime import datetime

import rclpy
from action_msgs.msg import GoalStatus
from geometry_msgs.msg import PoseStamped, Twist
from nav2_msgs.action import ComputePathToPose, NavigateToPose
from nav2_msgs.srv import SaveMap
from nav_msgs.msg import OccupancyGrid, Odometry
from rclpy.action import ActionClient
from rclpy.node import Node
from rclpy.qos import (
    DurabilityPolicy,
    QoSProfile,
    ReliabilityPolicy,
    qos_profile_sensor_data,
)
from rclpy.time import Time
from slam_toolbox.srv import SerializePoseGraph
from tf2_ros import Buffer, TransformException, TransformListener

from potner_exploration.action_outcome import (
    PathResultKind,
    classify_path_result,
)
from potner_exploration.exploration_state import (
    Decision,
    ExplorationController,
    ExplorationPolicy,
    ExplorationState,
)
from potner_exploration.frontier_analysis_worker import (
    AnalysisRequest,
    FrontierAnalysisWorker,
)
from potner_exploration.frontier_detector import (
    Frontier,
    FrontierConfig,
    GridMap,
    target_remains_local_frontier,
)
from potner_exploration.map_revision import MapRevisionGate
from potner_exploration.map_output import build_output_paths
from potner_exploration.sensor_freshness import (
    FreshnessLimits,
    FreshnessStatus,
    evaluate_freshness,
)


class ExplorationNode(Node):
    """SLAM 지도를 frontier 목표와 자동 저장으로 연결합니다."""

    def __init__(self):
        super().__init__("exploration")
        self._declare_parameters()

        self.frontier_config = FrontierConfig(
            free_threshold=self._param("free_threshold"),
            occupied_threshold=self._param("occupied_threshold"),
            min_cluster_size=self._param("min_cluster_size"),
            clearance_m=self._param("clearance_m"),
            information_gain_weight=self._param("information_gain_weight"),
            distance_weight=self._param("distance_weight"),
            blocked_radius_m=self._param("blocked_radius_m"),
        )
        self._freshness_limits = FreshnessLimits(
            map_timeout_s=self._param("map_timeout_s"),
            odom_timeout_s=self._param("odom_timeout_s"),
            tf_timeout_s=self._param("tf_timeout_s"),
        )
        self.controller = ExplorationController(
            ExplorationPolicy(
                goal_timeout_s=self._param("goal_timeout_s"),
                completion_hold_s=self._param("completion_hold_s"),
                max_consecutive_failures=self._param(
                    "max_consecutive_failures"
                ),
                max_exploration_s=self._param("max_exploration_s"),
                blacklist_ttl_s=self._param("blacklist_ttl_s"),
                save_timeout_s=self._param("save_timeout_s"),
                sensor_recovery_timeout_s=self._param(
                    "sensor_recovery_timeout_s"
                ),
            )
        )

        self._grid = None
        self._robot_pose = None
        self._last_map_at = None
        self._last_odom_at = None
        self._last_tf_at = None
        self._map_revision_gate = MapRevisionGate()
        self._analysis_worker = FrontierAnalysisWorker()
        self._path_candidates = []
        self._path_check_in_progress = False
        self._path_request_serial = 0
        self._active_path_request = None
        self._path_goal_handle = None
        self._path_candidate = None
        self._path_check_started_at = None
        self._navigation_request_in_progress = False
        self._navigation_request_serial = 0
        self._active_navigation_request = None
        self._navigation_request_started_at = None
        self._nav_goal_handle = None
        self._active_nav_target = None
        self._saving_started = False
        self._save_results = {"map": None, "posegraph": None}
        self._last_logged_state = None

        map_qos = QoSProfile(
            depth=1,
            reliability=ReliabilityPolicy.RELIABLE,
            durability=DurabilityPolicy.TRANSIENT_LOCAL,
        )
        self.create_subscription(
            OccupancyGrid,
            self._param("map_topic"),
            self._on_map,
            map_qos,
        )
        self.create_subscription(
            Odometry,
            self._param("odom_topic"),
            self._on_odom,
            qos_profile_sensor_data,
        )
        self._stop_pub = self.create_publisher(Twist, "cmd_vel_nav", 10)

        self._tf_buffer = Buffer()
        self._tf_listener = TransformListener(self._tf_buffer, self)
        self._path_client = ActionClient(
            self, ComputePathToPose, "compute_path_to_pose"
        )
        self._navigate_client = ActionClient(
            self, NavigateToPose, "navigate_to_pose"
        )
        self._map_save_client = self.create_client(
            SaveMap, self._param("map_save_service")
        )
        self._serialize_client = self.create_client(
            SerializePoseGraph, self._param("serialize_service")
        )

        self.create_timer(self._param("selection_period_s"), self._on_timer)
        self.create_timer(
            self._param("sensor_stop_publish_period_s"),
            self._maintain_sensor_stop,
        )
        self.get_logger().info("자율 frontier 탐색 노드가 시작되었습니다.")

    def _declare_parameters(self):
        self.declare_parameter("map_topic", "/map")
        self.declare_parameter("odom_topic", "/odom")
        self.declare_parameter("map_frame", "map")
        self.declare_parameter("base_frame", "base_footprint")
        self.declare_parameter("free_threshold", 20)
        self.declare_parameter("occupied_threshold", 65)
        self.declare_parameter("min_cluster_size", 5)
        self.declare_parameter("clearance_m", 0.20)
        self.declare_parameter("information_gain_weight", 1.0)
        self.declare_parameter("distance_weight", 1.0)
        self.declare_parameter("blocked_radius_m", 0.35)
        self.declare_parameter("goal_timeout_s", 90.0)
        self.declare_parameter("completion_hold_s", 15.0)
        self.declare_parameter("max_consecutive_failures", 5)
        self.declare_parameter("max_exploration_s", 1800.0)
        self.declare_parameter("blacklist_ttl_s", 120.0)
        self.declare_parameter("map_timeout_s", 6.0)
        self.declare_parameter("odom_timeout_s", 2.0)
        self.declare_parameter("selection_period_s", 1.0)
        self.declare_parameter("max_path_checks", 8)
        self.declare_parameter("path_check_timeout_s", 10.0)
        self.declare_parameter("navigation_accept_timeout_s", 10.0)
        self.declare_parameter("frontier_replan_distance_m", 0.75)
        self.declare_parameter("tf_timeout_s", 1.0)
        self.declare_parameter("sensor_recovery_timeout_s", 10.0)
        self.declare_parameter("sensor_stop_publish_period_s", 0.5)
        self.declare_parameter("save_timeout_s", 30.0)
        self.declare_parameter("map_output_dir", "~/potner_ws/maps")
        self.declare_parameter("map_name", "home")
        self.declare_parameter("map_save_service", "/map_saver/save_map")
        self.declare_parameter(
            "serialize_service", "/slam_toolbox/serialize_map"
        )
        self.declare_parameter("save_free_threshold", 0.25)
        self.declare_parameter("save_occupied_threshold", 0.65)

    def _param(self, name):
        return self.get_parameter(name).value

    def _seconds_now(self) -> float:
        return self.get_clock().now().nanoseconds * 1e-9

    def _on_map(self, msg: OccupancyGrid):
        try:
            grid = GridMap(
                width=msg.info.width,
                height=msg.info.height,
                resolution=msg.info.resolution,
                origin_x=msg.info.origin.position.x,
                origin_y=msg.info.origin.position.y,
                data=tuple(msg.data),
            )
            if grid != self._grid:
                self._map_revision_gate.mark_map()
            self._grid = grid
            self._last_map_at = self._seconds_now()
        except ValueError as exc:
            self.get_logger().error(f"잘못된 /map을 버립니다: {exc}")

    def _on_odom(self, _msg: Odometry):
        self._last_odom_at = self._seconds_now()

    def _update_robot_pose(self) -> bool:
        try:
            transform = self._tf_buffer.lookup_transform(
                self._param("map_frame"),
                self._param("base_frame"),
                Time(),
            )
        except TransformException:
            return False

        translation = transform.transform.translation
        rotation = transform.transform.rotation
        yaw = math.atan2(
            2.0 * (rotation.w * rotation.z + rotation.x * rotation.y),
            1.0 - 2.0 * (rotation.y * rotation.y + rotation.z * rotation.z),
        )
        self._robot_pose = (translation.x, translation.y, yaw)
        stamp = transform.header.stamp
        self._last_tf_at = stamp.sec + stamp.nanosec * 1e-9
        return True

    def _on_timer(self):
        now = self._seconds_now()
        tf_ready = self._update_robot_pose()

        if self.controller.state is ExplorationState.WAITING_FOR_SYSTEM:
            if self._system_ready(now, tf_ready):
                self._apply(self.controller.mark_ready(now))
            return

        sensor_status = self._sensor_status(now, tf_ready)
        decision = self.controller.tick(now, sensor_status.fresh)
        if not sensor_status.fresh:
            self.get_logger().warn(
                sensor_status.describe(),
                throttle_duration_sec=2.0,
            )
        self._apply(decision)
        if decision.state in {
            ExplorationState.COMPLETED,
            ExplorationState.FAILED,
            ExplorationState.SAVING_MAP,
            ExplorationState.WAITING_FOR_SENSORS,
        }:
            return

        if decision.state is ExplorationState.VERIFYING_COMPLETE:
            frontiers = self._poll_frontier_analysis(now)
            if frontiers:
                self._apply(self.controller.frontiers_found(now))
            return

        if decision.state is ExplorationState.NAVIGATING:
            if (
                self._navigation_request_in_progress
                and self._navigation_request_started_at is not None
                and now - self._navigation_request_started_at
                >= self._param("navigation_accept_timeout_s")
            ):
                self._apply(
                    self.controller.system_failed(
                        "navigation goal acceptance timed out"
                    )
                )
                return
            if (
                self._map_revision_gate.needs_check
                and not self._navigation_target_remains_frontier(now)
            ):
                self._active_nav_target = None
                self._apply(self.controller.goal_obsolete(now))
            return

        if (
            decision.state is ExplorationState.SELECTING_FRONTIER
            and self._path_check_in_progress
            and self._path_check_started_at is not None
            and now - self._path_check_started_at
            >= self._param("path_check_timeout_s")
        ):
            self._expire_path_check(now)
            return

        if (
            self.controller.state is ExplorationState.SELECTING_FRONTIER
            and not self._path_check_in_progress
            and not self._navigation_request_in_progress
            and self._nav_goal_handle is None
        ):
            self._start_frontier_selection(now)

    def _system_ready(self, now: float, tf_ready: bool) -> bool:
        return (
            self._grid is not None
            and self._last_odom_at is not None
            and self._sensor_status(now, tf_ready).fresh
            and self._path_client.server_is_ready()
            and self._navigate_client.server_is_ready()
        )

    def _sensor_status(self, now: float, tf_ready: bool) -> FreshnessStatus:
        return evaluate_freshness(
            now=now,
            last_map_at=self._last_map_at,
            last_odom_at=self._last_odom_at,
            last_tf_at=self._last_tf_at,
            tf_ready=tf_ready,
            limits=self._freshness_limits,
        )

    def _navigation_target_remains_frontier(self, now: float) -> bool:
        if self._active_nav_target is None or self._grid is None:
            return False
        target_x, target_y = self._active_nav_target
        remains = target_remains_local_frontier(
            self._grid,
            target_x,
            target_y,
            self.frontier_config,
            self._param("frontier_replan_distance_m"),
        )
        self._map_revision_gate.mark_checked()
        return remains

    def _start_frontier_selection(self, now: float):
        frontiers = self._poll_frontier_analysis(now)
        if frontiers is None:
            return
        if not frontiers:
            self._apply(self.controller.frontiers_missing(now))
            return

        self._path_candidates = frontiers[: self._param("max_path_checks")]
        self._try_next_path_candidate()

    def _poll_frontier_analysis(self, now: float) -> list[Frontier] | None:
        try:
            completed = self._analysis_worker.poll()
        except Exception as exc:
            self._apply(
                self.controller.system_failed(
                    f"frontier analysis failed: {exc}"
                )
            )
            return None

        if completed is not None:
            if self._map_revision_gate.accepts_analysis(
                completed.revision,
                bool(completed.analysis.frontiers),
            ):
                return list(completed.analysis.frontiers)

        if (
            self._grid is not None
            and self._robot_pose is not None
        ):
            x, y, _yaw = self._robot_pose
            self._analysis_worker.request(
                AnalysisRequest(
                    revision=self._map_revision_gate.revision,
                    grid=self._grid,
                    robot_x=x,
                    robot_y=y,
                    config=self.frontier_config,
                    blocked_points=self.controller.blocked_points(now),
                )
            )
        return None

    def _try_next_path_candidate(self):
        if not self.controller.accepts_new_goal:
            self._path_candidates = []
            self._cancel_path_check()
            return
        if not self._path_candidates:
            self._apply(self.controller.frontiers_missing(self._seconds_now()))
            return

        frontier = self._path_candidates.pop(0)
        goal = ComputePathToPose.Goal()
        goal.goal = self._pose_for_frontier(frontier)
        goal.planner_id = "GridBased"
        goal.use_start = False

        self._path_request_serial += 1
        request_id = self._path_request_serial
        self._active_path_request = request_id
        self._path_candidate = frontier
        self._path_check_started_at = self._seconds_now()
        self._path_check_in_progress = True
        try:
            future = self._path_client.send_goal_async(goal)
        except Exception as exc:
            self._clear_path_check()
            self._apply(
                self.controller.system_failed(f"path request failed: {exc}")
            )
            return
        future.add_done_callback(
            lambda completed, candidate=frontier, token=request_id: (
                self._on_path_goal_response(completed, candidate, token)
            )
        )

    def _on_path_goal_response(
        self, future, frontier: Frontier, request_id: int
    ):
        try:
            goal_handle = future.result()
        except Exception as exc:
            if request_id != self._active_path_request:
                return
            self._clear_path_check()
            self._apply(
                self.controller.system_failed(f"path request failed: {exc}")
            )
            return
        if request_id != self._active_path_request:
            if goal_handle.accepted:
                goal_handle.cancel_goal_async()
            return
        if not self.controller.accepts_new_goal:
            if goal_handle.accepted:
                goal_handle.cancel_goal_async()
            self._clear_path_check()
            return
        if not goal_handle.accepted:
            self._clear_path_check()
            self._apply(self.controller.system_failed("path goal rejected"))
            return
        self._path_goal_handle = goal_handle
        result_future = goal_handle.get_result_async()
        result_future.add_done_callback(
            lambda completed, candidate=frontier, token=request_id: (
                self._on_path_result(completed, candidate, token)
            )
        )

    def _on_path_result(self, future, frontier: Frontier, request_id: int):
        if request_id != self._active_path_request:
            return
        if not self.controller.accepts_new_goal:
            self._clear_path_check()
            return
        try:
            wrapped = future.result()
        except Exception as exc:
            self._clear_path_check()
            self._apply(
                self.controller.system_failed(f"path result failed: {exc}")
            )
            return
        outcome = classify_path_result(
            wrapped.status,
            len(wrapped.result.path.poses),
            succeeded_status=GoalStatus.STATUS_SUCCEEDED,
            aborted_status=GoalStatus.STATUS_ABORTED,
        )
        if outcome is PathResultKind.SYSTEM_FAILURE:
            self._clear_path_check()
            self._apply(
                self.controller.system_failed(
                    f"path action failed with status {wrapped.status}"
                )
            )
            return
        if outcome is PathResultKind.UNREACHABLE:
            self._reject_path_candidate(
                frontier,
                self._seconds_now(),
                "frontier has no valid Nav2 path",
            )
            return
        self._clear_path_check()
        self._send_navigation_goal(frontier)

    def _reject_path_candidate(
        self, frontier: Frontier, now: float, reason: str
    ):
        self._clear_path_check()
        if not self.controller.accepts_new_goal:
            return
        self._apply(
            self.controller.candidate_unreachable(
                now, (frontier.goal_x, frontier.goal_y), reason
            )
        )
        self._try_next_path_candidate()

    def _expire_path_check(self, now: float):
        if self._path_goal_handle is not None:
            self._path_goal_handle.cancel_goal_async()
        self._clear_path_check()
        if self.controller.accepts_new_goal:
            self._apply(self.controller.system_failed("path check timed out"))

    def _clear_path_check(self):
        self._path_check_in_progress = False
        self._active_path_request = None
        self._path_goal_handle = None
        self._path_candidate = None
        self._path_check_started_at = None

    def _cancel_path_check(self):
        self._path_request_serial += 1
        if self._path_goal_handle is not None:
            self._path_goal_handle.cancel_goal_async()
        self._clear_path_check()

    def _send_navigation_goal(self, frontier: Frontier):
        if not self.controller.accepts_new_goal:
            return
        now = self._seconds_now()
        if self._grid is None or not target_remains_local_frontier(
            self._grid,
            frontier.goal_x,
            frontier.goal_y,
            self.frontier_config,
            self._param("frontier_replan_distance_m"),
        ):
            self._reject_path_candidate(
                frontier,
                now,
                "frontier changed while checking its path",
            )
            return
        self._apply(
            self.controller.goal_selected(
                now, (frontier.goal_x, frontier.goal_y)
            )
        )
        self._active_nav_target = (frontier.goal_x, frontier.goal_y)
        self._map_revision_gate.reset()
        goal = NavigateToPose.Goal()
        goal.pose = self._pose_for_frontier(frontier)
        self._navigation_request_serial += 1
        request_id = self._navigation_request_serial
        self._active_navigation_request = request_id
        self._navigation_request_started_at = now
        self._navigation_request_in_progress = True
        try:
            future = self._navigate_client.send_goal_async(goal)
        except Exception as exc:
            self._clear_navigation_request()
            self._apply(
                self.controller.system_failed(
                    f"navigation request failed: {exc}"
                )
            )
            return
        future.add_done_callback(
            lambda completed, token=request_id: (
                self._on_navigation_goal_response(completed, token)
            )
        )

    def _on_navigation_goal_response(self, future, request_id: int):
        try:
            goal_handle = future.result()
        except Exception as exc:
            if request_id == self._active_navigation_request:
                self._clear_navigation_request()
                self._active_nav_target = None
                self._apply(
                    self.controller.system_failed(
                        f"navigation request failed: {exc}"
                    )
                )
            return
        if (
            request_id != self._active_navigation_request
            or self.controller.state is not ExplorationState.NAVIGATING
        ):
            if goal_handle.accepted:
                goal_handle.cancel_goal_async()
            return
        self._clear_navigation_request()
        if not goal_handle.accepted:
            self._active_nav_target = None
            self._apply(
                self.controller.system_failed("navigation goal rejected")
            )
            return
        self._nav_goal_handle = goal_handle
        goal_handle.get_result_async().add_done_callback(
            lambda completed, handle=goal_handle: self._on_navigation_result(
                completed, handle
            )
        )

    def _on_navigation_result(self, future, goal_handle):
        if goal_handle is not self._nav_goal_handle:
            return
        self._nav_goal_handle = None
        self._active_nav_target = None
        if self.controller.state is not ExplorationState.NAVIGATING:
            return
        try:
            wrapped = future.result()
            succeeded = wrapped.status == GoalStatus.STATUS_SUCCEEDED
        except Exception as exc:
            self.get_logger().warn(f"Nav2 결과 수신 실패: {exc}")
            succeeded = False

        now = self._seconds_now()
        if succeeded:
            self._apply(self.controller.goal_succeeded(now))
        else:
            self._apply(self.controller.goal_failed(now, "navigation failed"))

    def _pose_for_frontier(self, frontier: Frontier) -> PoseStamped:
        pose = PoseStamped()
        pose.header.frame_id = self._param("map_frame")
        pose.header.stamp = self.get_clock().now().to_msg()
        pose.pose.position.x = frontier.goal_x
        pose.pose.position.y = frontier.goal_y
        robot_x, robot_y, _yaw = self._robot_pose
        heading = math.atan2(
            frontier.goal_y - robot_y,
            frontier.goal_x - robot_x,
        )
        pose.pose.orientation.z = math.sin(heading / 2.0)
        pose.pose.orientation.w = math.cos(heading / 2.0)
        return pose

    def _apply(self, decision: Decision):
        if decision.state is not self._last_logged_state:
            self.get_logger().info(
                f"탐색 상태: {decision.state.name}"
                + (f" ({decision.reason})" if decision.reason else "")
            )
            self._last_logged_state = decision.state
        elif decision.reason:
            self.get_logger().warn(decision.reason)

        if decision.cancel_goal:
            self._analysis_worker.cancel()
            self._cancel_path_check()
            self._cancel_navigation()
        if decision.publish_stop:
            self._publish_stop()
        if decision.save_map:
            self._start_map_save()

    def _cancel_navigation(self):
        self._active_nav_target = None
        self._navigation_request_serial += 1
        self._clear_navigation_request()
        if self._nav_goal_handle is None:
            return
        self._nav_goal_handle.cancel_goal_async()
        self._nav_goal_handle = None

    def _clear_navigation_request(self):
        self._navigation_request_in_progress = False
        self._active_navigation_request = None
        self._navigation_request_started_at = None

    def _publish_stop(self):
        stop = Twist()
        for _ in range(5):
            self._stop_pub.publish(stop)

    def _maintain_sensor_stop(self):
        if self.controller.state is ExplorationState.WAITING_FOR_SENSORS:
            self._publish_stop()

    def _start_map_save(self):
        if self._saving_started:
            return
        self._saving_started = True
        self._publish_stop()

        if not self._map_save_client.service_is_ready():
            self.get_logger().error("map saver 서비스가 준비되지 않았습니다.")
            self._apply(self.controller.mark_save_finished(False))
            return
        if not self._serialize_client.service_is_ready():
            self.get_logger().error("slam_toolbox serialize 서비스가 준비되지 않았습니다.")
            self._apply(self.controller.mark_save_finished(False))
            return

        try:
            paths = build_output_paths(
                self._param("map_output_dir"),
                self._param("map_name"),
                datetime.now(),
            )
        except (OSError, ValueError) as exc:
            self.get_logger().error(f"지도 출력 경로 생성 실패: {exc}")
            self._apply(self.controller.mark_save_finished(False))
            return

        map_request = SaveMap.Request()
        map_request.map_topic = self._param("map_topic")
        map_request.map_url = str(paths.map_base)
        map_request.image_format = "pgm"
        map_request.map_mode = "trinary"
        map_request.free_thresh = self._param("save_free_threshold")
        map_request.occupied_thresh = self._param("save_occupied_threshold")
        map_future = self._map_save_client.call_async(map_request)
        map_future.add_done_callback(
            lambda completed: self._on_save_result("map", completed)
        )

        graph_request = SerializePoseGraph.Request()
        graph_request.filename = str(paths.posegraph_base)
        graph_future = self._serialize_client.call_async(graph_request)
        graph_future.add_done_callback(
            lambda completed: self._on_save_result("posegraph", completed)
        )

    def _on_save_result(self, part: str, future):
        if self.controller.state is not ExplorationState.SAVING_MAP:
            return
        try:
            response = future.result()
            if part == "map":
                success = bool(response.result)
            else:
                success = response.result == response.RESULT_SUCCESS
        except Exception as exc:
            self.get_logger().error(f"{part} 저장 호출 실패: {exc}")
            success = False
        self._save_results[part] = success

        if all(result is not None for result in self._save_results.values()):
            completed = all(self._save_results.values())
            self._apply(self.controller.mark_save_finished(completed))

    def destroy_node(self):
        self._cancel_path_check()
        self._cancel_navigation()
        self._publish_stop()
        self._analysis_worker.shutdown()
        super().destroy_node()


def main(args=None):
    rclpy.init(args=args)
    node = ExplorationNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
