# Potner 실내 자동 SLAM 탐색

ESP32 DevKit V4의 엔코더 오도메트리와 YDLIDAR X4 Pro의 `/scan`을 이용해
Jetson Orin Nano에서 미지 공간을 자동 탐색하고 지도를 저장하는 절차입니다.

## 구성

```text
X4 Pro /scan + ESP32 /odom -> slam_toolbox -> /map
                                  |
                                  v
                         frontier exploration
                                  |
                                  v
               Nav2 -> cmd_vel_nav -> twist_mux -> cmd_vel_requested
                                                     |
                              X4 Pro + ToF -> safety -> cmd_vel -> ESP32
```

탐색 노드는 OccupancyGrid에서 자유 공간과 미탐색 공간의 경계를 찾고,
Nav2로 접근 가능한 목표만 선택합니다. frontier가 15초 동안 더 생기지 않으면
정지한 뒤 지도 이미지와 slam_toolbox pose graph를 자동 저장합니다.

## 설치와 빌드

Jetson은 Ubuntu 22.04, ROS 2 Humble 기준입니다.

```bash
sudo apt update
sudo apt install -y \
  ros-humble-navigation2 ros-humble-nav2-bringup \
  ros-humble-slam-toolbox ros-humble-twist-mux \
  ros-humble-robot-state-publisher ros-humble-xacro \
  ros-humble-tf2-tools ros-humble-rviz2

cd ~/potner_ws
rosdep install --from-paths src --ignore-src -r -y
colcon build --symlink-install
source install/setup.bash
```

X4 Pro 드라이버와 `/dev/ydlidar` udev 설정은 루트 `README.md`의
“LiDAR 드라이버 설치” 절차를 먼저 완료합니다.

## 실기 전에 반드시 보정할 값

- `src/potner_bringup/config/potner_params.yaml`
  - `wheel_separation`: 좌우 바퀴 접지 중심 사이의 실제 거리(m)
  - `wheel_diameter`: 장착 상태에서의 실제 유효 지름(m)
- `src/potner_description/urdf/potner.urdf.xacro`
  - `wheel_separation`: 위 값과 동일하게 유지
  - `lidar_height`: 바닥부터 LiDAR 스캔 회전 중심까지의 높이

왼쪽과 오른쪽 바퀴를 전진 방향으로 굴렸을 때 엔코더 카운트가 모두 증가해야
합니다. 한쪽이 감소하면 펌웨어를 바꾸기 전에 해당 엔코더의 A/B 배선을 서로
바꿉니다.

## 안전한 첫 실행

처음에는 계단, 현관, 유리문이 없는 좁고 통제된 공간에서 시험합니다.
화분은 내리고, 전원을 즉시 끌 수 있는 사람이 로봇 옆에 있어야 합니다.

### 1. 바퀴를 띄우고 센서와 TF 확인

```bash
ros2 launch potner_bringup robot.launch.py use_camera:=false

ros2 topic hz /scan
ros2 topic hz /odom
ros2 run tf2_ros tf2_echo odom base_footprint
ros2 run tf2_ros tf2_echo base_footprint laser_frame
```

기대 결과:

- `/scan`: X4 Pro 기준 약 10~11Hz
- `/odom`: 약 30Hz
- TF: `odom -> base_footprint -> base_link -> laser_frame`가 끊기지 않음
- 정지 상태에서 `/odom` 위치가 계속 크게 변하지 않음

확인 후 실행한 launch를 종료합니다. 두 번째 launch와 동시에 띄우면 장치
노드와 TF가 중복됩니다.

### 2. 자동 탐색 시작

```bash
ros2 launch potner_bringup exploration.launch.py \
  map_output_dir:=$HOME/potner_ws/maps \
  map_name:=home \
  use_rviz:=true \
  use_camera:=false
```

이 명령 하나가 로봇 본체, X4 Pro, slam_toolbox, Nav2, map saver, frontier
탐색 노드를 실행합니다. 별도 `robot.launch.py`, `slam.launch.py`,
`nav2.launch.py`를 동시에 실행하지 마세요.

일반 장애물은 Nav2가 costmap을 이용해 우회합니다. 너무 가까운 벽 때문에
안전 필터가 개입하면 위험한 속도 성분만 제거합니다. 전방 벽 앞에서는 전진을
막되 안전한 회전과, 후방 LiDAR 측정이 유효하고 0.25m보다 먼 경우의 후진을
허용합니다. Nav2는 후진·회전·재계획 후에도 실패한 목표를 탐색 노드에 돌려주고,
탐색 노드는 그 위치를 일시적으로 제외한 뒤 다른 frontier를 선택합니다.

후방 LiDAR 표본이 없으면 맹목적으로 후진하지 않습니다. `/scan` 또는 선택 속도
명령이 0.5초 이상 끊겨도 최종 `/cmd_vel`은 0이 됩니다.

### 3. 실행 중 상태 확인

다른 터미널에서 다음을 확인합니다.

```bash
source ~/potner_ws/install/setup.bash
ros2 topic hz /scan
ros2 topic hz /odom
ros2 run tf2_ros tf2_echo map base_footprint
ros2 action list | grep -E 'navigate_to_pose|compute_path_to_pose'
ros2 topic echo /cmd_vel_nav
ros2 topic echo /cmd_vel
```

로그의 주요 상태는 다음과 같습니다.

- `WAITING_FOR_SYSTEM`: 지도, 오도메트리, TF 또는 Nav2 액션 서버 대기
- `SELECTING_FRONTIER`: 다음 미탐색 경계 계산
- `NAVIGATING`: Nav2가 선택한 경계로 이동 중
- `RECOVERY`: 실패 지점을 일시 차단하고 다른 경계 선택
- `VERIFYING_COMPLETE`: 새 frontier가 생기는지 15초 확인
- `SAVING_MAP`: 로봇 정지 후 지도와 pose graph 저장
- `COMPLETED`: 자동 저장 완료
- `FAILED`: 센서 중단, 연속 실패, 저장 실패 또는 최대 시간 초과

## 저장 결과

기본 파일명에는 실행 완료 시각이 붙습니다.

```text
~/potner_ws/maps/home_20260805_143045.yaml
~/potner_ws/maps/home_20260805_143045.pgm
~/potner_ws/maps/home_20260805_143045_posegraph.posegraph
~/potner_ws/maps/home_20260805_143045_posegraph.data
```

YAML/PGM은 Nav2와 RViz에서 사용하는 2D 지도이고, posegraph/data는 나중에
slam_toolbox로 매핑을 이어갈 때 사용합니다.

저장한 지도를 확인하려면 탐색 launch를 종료한 뒤 실행합니다.

```bash
ros2 launch potner_bringup robot.launch.py
ros2 launch potner_bringup nav2.launch.py \
  map:=$HOME/potner_ws/maps/home_20260805_143045.yaml
```

RViz의 “2D Pose Estimate”로 초기 위치를 지정하고 벽과 LiDAR 스캔이 겹치는지
확인합니다.

## 튜닝

탐색 정책은 `src/potner_bringup/config/exploration.yaml`에 있습니다.

- 작은 지도 노이즈를 목표로 잡으면 `min_cluster_size`를 올립니다.
- 좁은 곳을 지나치게 피하면 `clearance_m`을 조금씩 낮춥니다. 로봇 반경
  `0.14m`보다 작게 내리기 전에 실제 외경과 돌출물을 다시 측정합니다.
- 먼 큰 방보다 가까운 문을 먼저 가게 하려면 `distance_weight`를 올립니다.
- 목표를 너무 빨리 포기하면 `goal_timeout_s`를 올립니다.

주행 속도와 costmap은 `src/potner_bringup/config/nav2_params.yaml`에 있습니다.
현재 기본값은 이전보다 30% 낮춘 최대 선속도 `0.084m/s`, 최대 각속도
`0.35rad/s`입니다. `base_driver` 바퀴 속도 hard cap도 `0.175m/s`로 낮춰
수동 조작과 도킹을 포함한 모든 소프트웨어 주행 명령에 상한을 적용합니다.

## 문제 해결

### `WAITING_FOR_SYSTEM`에서 진행하지 않음

```bash
ros2 topic list | grep -E '^/map$|^/odom$|^/scan$'
ros2 run tf2_ros tf2_echo map base_footprint
ros2 action list
```

`/map`이 없으면 X4 Pro와 slam_toolbox 로그를, TF가 없으면 엔코더 피드백과
`base_driver` 로그를 먼저 확인합니다.

### 지도가 겹치거나 회전할수록 찢어짐

`wheel_separation`, 양쪽 엔코더 방향, LiDAR 고정 상태를 확인합니다. 직진은
맞는데 제자리 회전 각도만 틀리면 대부분 축간거리 보정 문제입니다.

### 같은 구역에서 반복 실패

Nav2 global/local costmap에서 로봇 반경과 inflation을 확인합니다. 통로가 실제
로봇 폭보다 좁으면 `clearance_m`을 낮춰 억지로 통과시키지 말고 해당 공간을
탐색 대상에서 제외합니다.

### 즉시 멈추기

터미널에서 `Ctrl+C`를 누르면 탐색 노드가 Nav2 목표를 취소하고 정지 명령을
발행합니다. 소프트웨어 정지만 믿지 말고 첫 실기에는 물리 전원 차단 수단을
항상 준비합니다. Jetson 통신이 끊겨도 ESP32의 500ms 워치독이 모터를
정지시킵니다.
