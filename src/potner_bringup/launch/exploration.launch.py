"""로봇이 미지 공간을 자동 탐색하고 지도를 저장합니다."""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, IncludeLaunchDescription
from launch.conditions import IfCondition
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import EnvironmentVariable, LaunchConfiguration
from launch_ros.actions import Node


def generate_launch_description():
    bringup_share = get_package_share_directory("potner_bringup")
    description_share = get_package_share_directory("potner_description")
    params = os.path.join(bringup_share, "config", "nav2_params.yaml")
    slam_params = os.path.join(bringup_share, "config", "slam_toolbox.yaml")
    exploration_params = os.path.join(
        bringup_share, "config", "exploration.yaml"
    )

    use_sim_time = LaunchConfiguration("use_sim_time")
    use_rviz = LaunchConfiguration("use_rviz")
    use_camera = LaunchConfiguration("use_camera")
    map_output_dir = LaunchConfiguration("map_output_dir")
    map_name = LaunchConfiguration("map_name")

    return LaunchDescription([
        DeclareLaunchArgument("use_sim_time", default_value="false"),
        DeclareLaunchArgument("use_rviz", default_value="true"),
        DeclareLaunchArgument("use_camera", default_value="false"),
        DeclareLaunchArgument(
            "map_output_dir",
            default_value=[EnvironmentVariable("HOME"), "/potner_ws/maps"],
        ),
        DeclareLaunchArgument("map_name", default_value="home"),
        IncludeLaunchDescription(
            PythonLaunchDescriptionSource(
                os.path.join(bringup_share, "launch", "robot.launch.py")
            ),
            launch_arguments={
                "use_camera": use_camera,
                "use_lidar": "true",
            }.items(),
        ),
        Node(
            package="slam_toolbox",
            executable="async_slam_toolbox_node",
            name="slam_toolbox",
            parameters=[slam_params, {"use_sim_time": use_sim_time}],
            output="screen",
        ),
        IncludeLaunchDescription(
            PythonLaunchDescriptionSource(
                os.path.join(
                    bringup_share, "launch", "navigation_core.launch.py"
                )
            ),
            launch_arguments={
                "params_file": params,
                "use_sim_time": use_sim_time,
            }.items(),
        ),
        Node(
            package="nav2_map_server",
            executable="map_saver_server",
            name="map_saver",
            parameters=[params, {"use_sim_time": use_sim_time}],
            output="screen",
        ),
        Node(
            package="nav2_lifecycle_manager",
            executable="lifecycle_manager",
            name="lifecycle_manager_map_saver",
            parameters=[{
                "use_sim_time": use_sim_time,
                "autostart": True,
                "node_names": ["map_saver"],
            }],
            output="screen",
        ),
        Node(
            package="potner_exploration",
            executable="exploration",
            name="exploration",
            parameters=[
                exploration_params,
                {
                    "use_sim_time": use_sim_time,
                    "map_output_dir": map_output_dir,
                    "map_name": map_name,
                },
            ],
            output="screen",
        ),
        Node(
            package="rviz2",
            executable="rviz2",
            condition=IfCondition(use_rviz),
            arguments=[
                "-d",
                os.path.join(description_share, "rviz", "potner.rviz"),
            ],
        ),
    ])
