"""선택된 주행 명령을 LiDAR와 ToF로 검증해 최종 속도를 발행합니다.

twist_mux는 Nav2, 도킹, 수동 명령 중 하나를 ``cmd_vel_requested``로
선택합니다. 이 노드는 위험한 방향의 속도 성분만 제거해 ``cmd_vel``로
발행합니다. 따라서 전방의 정적 벽은 전진을 막지만 안전한 후진과 회전 복구는
막지 않습니다. scan이나 선택 명령이 끊기면 fail-safe로 완전히 정지합니다.
"""

import rclpy
from geometry_msgs.msg import Twist
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import LaserScan, Range
from std_msgs.msg import Bool

from potner_mission.safety_policy import (
    DirectionalSafety,
    SafetyConfig,
    inputs_fresh,
    observe_scan,
)


class SafetyNode(Node):
    def __init__(self):
        super().__init__("safety")

        self.declare_parameter("scan_stop_distance", 0.25)
        self.declare_parameter("bumper_stop_distance", 0.12)
        self.declare_parameter("front_arc_deg", 60.0)
        self.declare_parameter("rear_arc_deg", 60.0)
        self.declare_parameter("side_arc_deg", 120.0)
        self.declare_parameter("clear_hysteresis", 0.08)
        self.declare_parameter("min_valid_samples", 1)
        self.declare_parameter("scan_timeout", 0.5)
        self.declare_parameter("command_timeout", 0.5)
        self.declare_parameter("publish_rate", 20.0)

        self._front_arc_deg = self._param("front_arc_deg")
        self._rear_arc_deg = self._param("rear_arc_deg")
        self._side_arc_deg = self._param("side_arc_deg")
        self._scan_timeout = self._param("scan_timeout")
        self._command_timeout = self._param("command_timeout")
        self._policy = DirectionalSafety(
            SafetyConfig(
                stop_distance=self._param("scan_stop_distance"),
                bumper_stop_distance=self._param("bumper_stop_distance"),
                clear_hysteresis=self._param("clear_hysteresis"),
                min_valid_samples=self._param("min_valid_samples"),
            )
        )

        self._requested = Twist()
        self._last_scan_at = None
        self._last_command_at = None

        self.create_subscription(
            Twist, "cmd_vel_requested", self._on_command, 10
        )
        self.create_subscription(
            LaserScan, "scan", self._on_scan, qos_profile_sensor_data
        )
        self.create_subscription(
            Range,
            "bumper/left",
            lambda message: self._on_bumper("left", message),
            qos_profile_sensor_data,
        )
        self.create_subscription(
            Range,
            "bumper/right",
            lambda message: self._on_bumper("right", message),
            qos_profile_sensor_data,
        )

        self._cmd_pub = self.create_publisher(Twist, "cmd_vel", 10)
        self._state_pub = self.create_publisher(Bool, "safety/blocked", 10)

        publish_rate = self._param("publish_rate")
        if publish_rate <= 0.0:
            raise ValueError("publish_rate must be positive")
        self.create_timer(1.0 / publish_rate, self._tick)

        self.get_logger().info(
            "방향별 safety 필터 시작: 위험 방향만 차단하고 안전한 복구는 허용합니다."
        )

    def _param(self, name):
        return self.get_parameter(name).value

    def _seconds_now(self):
        return self.get_clock().now().nanoseconds * 1e-9

    def _on_command(self, message: Twist):
        self._requested = message
        self._last_command_at = self._seconds_now()

    def _on_scan(self, message: LaserScan):
        clearance = observe_scan(
            ranges=message.ranges,
            angle_min=message.angle_min,
            angle_increment=message.angle_increment,
            range_min=message.range_min,
            range_max=message.range_max,
            front_arc_deg=self._front_arc_deg,
            rear_arc_deg=self._rear_arc_deg,
            side_arc_deg=self._side_arc_deg,
        )
        self._policy.update_scan(clearance)
        self._last_scan_at = self._seconds_now()

    def _on_bumper(self, side: str, message: Range):
        self._policy.update_bumper(side, message.range)

    def _tick(self):
        now = self._seconds_now()
        fresh = inputs_fresh(
            now=now,
            scan_stamp=self._last_scan_at,
            command_stamp=self._last_command_at,
            scan_timeout=self._scan_timeout,
            command_timeout=self._command_timeout,
        )

        output = Twist()
        reasons = ()
        limited = not fresh
        if fresh:
            result = self._policy.filter(
                self._requested.linear.x,
                self._requested.angular.z,
            )
            output.linear.x = result.linear_x
            output.angular.z = result.angular_z
            limited = result.limited
            reasons = result.reasons
        else:
            reasons = ("stale_scan_or_command",)

        self._cmd_pub.publish(output)
        self._state_pub.publish(Bool(data=limited))

        if limited:
            self.get_logger().warn(
                "주행 안전 제한 적용: " + ", ".join(reasons),
                throttle_duration_sec=2.0,
            )


def main(args=None):
    rclpy.init(args=args)
    node = SafetyNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
