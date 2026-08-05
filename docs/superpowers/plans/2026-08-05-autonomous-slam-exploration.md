# Autonomous SLAM Exploration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jetson Orin Nano, ESP32 엔코더 오도메트리, YDLIDAR X4 Pro를 이용해 미지 실내 공간을 자동 탐색하고 지도와 pose graph를 저장한다.

**Architecture:** ROS 비의존 frontier 검출기와 탐색 상태 머신을 새 `potner_exploration` 패키지에 두고, 얇은 rclpy 노드가 Nav2 액션과 지도 저장 서비스를 연결한다. SLAM 탐색 launch는 AMCL 없이 slam_toolbox와 Nav2 navigation 서버를 함께 실행하며 모든 Nav2 속도 명령은 twist_mux를 거친다.

**Tech Stack:** Python 3.10, ROS 2 Humble, rclpy, nav2_msgs, slam_toolbox, pytest, YAML, ROS 2 launch

## Global Constraints

- ESP32는 기존 모터 PID, 엔코더 디코딩, 500ms 워치독을 유지한다.
- frontier 핵심 로직과 상태 머신은 ROS를 import하지 않는다.
- 외부 탐색 패키지와 새 Python 런타임 의존성을 추가하지 않는다.
- TF는 `map -> odom -> base_footprint -> base_link -> laser_frame` 단일 트리를 사용한다.
- SLAM 탐색 중 AMCL과 map server는 실행하지 않는다.
- Nav2의 속도 출력은 `cmd_vel_nav`로 remap해 twist_mux를 반드시 통과한다.
- 기본 최대 속도는 선속도 `0.12m/s`, 각속도 `0.5rad/s`로 제한한다.
- 사용자 요청에 따라 git commit을 생성하지 않는다.

---

## File Structure

- `src/potner_exploration/potner_exploration/frontier_detector.py`: OccupancyGrid와 독립적인 지도 모델, frontier 군집화·필터·점수화
- `src/potner_exploration/potner_exploration/exploration_state.py`: 시간 제한, 완료 확인, 실패 차단을 관리하는 순수 상태 머신
- `src/potner_exploration/potner_exploration/exploration_node.py`: ROS 토픽, Nav2 액션, 저장 서비스 어댑터
- `src/potner_exploration/setup.py`, `setup.cfg`, `package.xml`, `resource/potner_exploration`: ament_python 패키지 정의
- `src/potner_bringup/config/exploration.yaml`: 탐색 정책 파라미터
- `src/potner_bringup/config/nav2_params.yaml`: 실제 실행 가능한 Nav2 서버·costmap·속도 설정
- `src/potner_bringup/launch/navigation_core.launch.py`: twist_mux를 우회하지 않는 공용 Nav2 navigation 서버
- `src/potner_bringup/launch/exploration.launch.py`: 본체, SLAM, 공용 Nav2 navigation, map saver, 탐색 노드 통합
- `src/potner_bringup/launch/nav2.launch.py`: 저장 지도 운용에서도 Nav2 출력 remap 보장
- `src/potner_base/potner_base/base_driver_node.py`: 동적 TF와 Odometry child frame을 `base_footprint`로 수정
- `src/potner_bringup/config/potner_params.yaml`: base frame 설정 추가
- `tests/test_frontier_detector.py`: frontier 알고리즘 단위 테스트
- `tests/test_exploration_state.py`: 상태 전이 단위 테스트
- `tests/test_slam_exploration_config.py`: TF, launch, YAML 계약 정적 테스트
- `docs/AUTONOMOUS_SLAM.md`: 설치, 실행, 튜닝, 실기 검증 절차

---

### Task 1: TF와 Nav2 명령 경로 계약 수정

**Files:**
- Modify: `src/potner_base/potner_base/base_driver_node.py`
- Modify: `src/potner_bringup/config/potner_params.yaml`
- Modify: `src/potner_bringup/launch/nav2.launch.py`
- Test: `tests/test_slam_exploration_config.py`

**Interfaces:**
- Produces: `base_driver.base_frame == "base_footprint"`, `/odom.child_frame_id == base_frame`, `odom -> base_frame` TF
- Produces: 저장 지도와 탐색 모드가 공유하는 안전한 Nav2 controller remap

- [ ] **Step 1: 계약을 검사하는 실패 테스트 작성**

```python
def test_base_driver_uses_base_footprint_as_dynamic_child():
    source = BASE_DRIVER.read_text(encoding="utf-8")
    assert 'declare_parameter("base_frame", "base_footprint")' in source
    assert 'msg.child_frame_id = self.base_frame' in source
    assert 'tf.child_frame_id = self.base_frame' in source

def test_saved_map_navigation_remaps_cmd_vel_through_mux():
    source = NAV2_LAUNCH.read_text(encoding="utf-8")
    assert "navigation_core.launch.py" in source
```

- [ ] **Step 2: 실패 확인**

Run: `python -m pytest tests/test_slam_exploration_config.py -v`
Expected: base frame 파라미터와 SetRemap이 없어 FAIL

- [ ] **Step 3: 최소 구현**

`BaseDriver.__init__`에서 `base_frame`을 선언·저장하고 `_publish_odom`의
Odometry 및 Transform child frame에 사용한다. `potner_params.yaml`에 같은
기본값을 넣는다. 저장 지도와 탐색 launch가 공용 `navigation_core.launch.py`를
사용하고 controller 출력을 `cmd_vel_nav`로 remap한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_slam_exploration_config.py tests/test_config_consistency.py -v`
Expected: PASS

---

### Task 2: Frontier 검출·군집화·점수화

**Files:**
- Create: `src/potner_exploration/potner_exploration/__init__.py`
- Create: `src/potner_exploration/potner_exploration/frontier_detector.py`
- Test: `tests/test_frontier_detector.py`

**Interfaces:**
- Produces: `GridMap(width, height, resolution, origin_x, origin_y, data)`
- Produces: `FrontierConfig(free_threshold, occupied_threshold, min_cluster_size, clearance_m, information_gain_weight, distance_weight, blocked_radius_m)`
- Produces: `Frontier(cells, goal_cell, goal_x, goal_y, size_m, score)`
- Produces: `find_frontiers(grid: GridMap, robot_x: float, robot_y: float, config: FrontierConfig, blocked_points: Sequence[tuple[float, float]] = ()) -> list[Frontier]`
- Produces: `world_to_cell(grid, x, y) -> tuple[int, int] | None`, `cell_to_world(grid, col, row) -> tuple[float, float]`

- [ ] **Step 1: 유효성·좌표 변환 실패 테스트 작성**

```python
def test_grid_rejects_wrong_data_size():
    with pytest.raises(ValueError, match="data length"):
        GridMap(2, 2, 0.05, 0.0, 0.0, (0, 0, 0))

def test_cell_world_round_trip_uses_cell_center():
    grid = GridMap(3, 2, 0.5, -1.0, 2.0, (0,) * 6)
    assert cell_to_world(grid, 1, 0) == pytest.approx((-0.25, 2.25))
    assert world_to_cell(grid, -0.25, 2.25) == (1, 0)
```

- [ ] **Step 2: 실패 확인 후 `GridMap`과 변환 최소 구현**

Run: `python -m pytest tests/test_frontier_detector.py -v`
Expected: import 또는 symbol 오류로 FAIL

- [ ] **Step 3: frontier 경계·군집 실패 테스트 추가**

3x5 이상의 지도에서 `-1` 미지 셀 옆의 `0` 자유 셀만 frontier가 되고,
대각선으로 닿은 셀이 하나의 8연결 군집이 되며 작은 군집이 제거되는 예제를
명시한다.

- [ ] **Step 4: reachable flood fill과 8연결 군집 구현**

로봇 셀에서 자유 셀만 flood fill해 도달 가능한 영역을 구한다. 해당 영역 중
4방향 이웃에 미지 셀이 있는 셀을 frontier로 표시하고, frontier끼리는
8방향으로 군집화한다.

- [ ] **Step 5: 안전거리·차단·점수 테스트 추가**

장애물과 `clearance_m`보다 가까운 군집, `blocked_radius_m` 안의 목표가
제거되는지 확인한다. 크기가 큰 frontier의 정보 이득과 가까운 frontier의
거리 비용이 설정 가중치대로 정렬되는지도 검증한다.

- [ ] **Step 6: brushfire 거리와 목표 선택 구현**

장애물 셀을 시작점으로 8방향 Dijkstra를 수행해 셀별 장애물 거리를 만든다.
각 군집에서 centroid와 가장 가깝고 안전한 셀을 goal로 고른다. 점수 내림차순,
동점이면 거리 오름차순으로 결정론적으로 정렬한다.

- [ ] **Step 7: 전체 테스트 확인**

Run: `python -m pytest tests/test_frontier_detector.py -v`
Expected: PASS

---

### Task 3: 탐색 상태 머신

**Files:**
- Create: `src/potner_exploration/potner_exploration/exploration_state.py`
- Test: `tests/test_exploration_state.py`

**Interfaces:**
- Produces: `ExplorationState` enum
- Produces: `ExplorationPolicy(goal_timeout_s, completion_hold_s, max_consecutive_failures, max_exploration_s, blacklist_ttl_s)`
- Produces: `Decision(state, cancel_goal=False, publish_stop=False, save_map=False, reason="")`
- Produces: `ExplorationController(policy)` with `mark_ready`, `goal_selected`, `goal_succeeded`, `goal_failed`, `frontiers_missing`, `frontiers_found`, `tick`, `mark_save_finished`, `blocked_points`

- [ ] **Step 1: 준비·목표 성공 전이 실패 테스트 작성**

```python
def test_ready_select_navigate_success_cycle():
    ctl = ExplorationController(ExplorationPolicy())
    assert ctl.mark_ready(0).state is ExplorationState.SELECTING_FRONTIER
    assert ctl.goal_selected(1, (2.0, 3.0)).state is ExplorationState.NAVIGATING
    assert ctl.goal_succeeded(2).state is ExplorationState.SELECTING_FRONTIER
```

- [ ] **Step 2: 실패 확인 후 enum, dataclass, 정상 전이 구현**

Run: `python -m pytest tests/test_exploration_state.py -v`
Expected: import 또는 symbol 오류로 FAIL

- [ ] **Step 3: 완료 확인·시간 제한·실패 차단 테스트 추가**

frontier 부재가 `completion_hold_s`보다 짧으면 VERIFYING에 머물고, 시간을
채우면 `save_map=True`인 SAVING_MAP이 되는지 확인한다. 목표 시간 초과,
센서 stale, 전체 탐색 시간 초과는 `cancel_goal=True`, `publish_stop=True`를
반환해야 한다. 실패 지점은 TTL 동안 `blocked_points(now)`에 포함돼야 한다.

- [ ] **Step 4: 시간·복구 정책 구현**

모든 시간은 호출자가 전달한 단조 증가 초 값만 사용한다. 목표 실패 시 연속
실패를 증가시키고 지점별 만료시간을 저장한다. 성공 시 연속 실패를 0으로
되돌린다. 최대 실패 또는 전체 제한시간은 FAILED로 전환한다.

- [ ] **Step 5: 저장 결과 테스트 및 구현**

`mark_save_finished(True)`는 COMPLETED, `False`는 정지 요청을 포함한 FAILED를
반환하도록 검증하고 구현한다.

- [ ] **Step 6: 전체 테스트 확인**

Run: `python -m pytest tests/test_exploration_state.py -v`
Expected: PASS

---

### Task 4: ROS 2 탐색 노드와 패키지

**Files:**
- Create: `src/potner_exploration/setup.py`
- Create: `src/potner_exploration/setup.cfg`
- Create: `src/potner_exploration/package.xml`
- Create: `src/potner_exploration/resource/potner_exploration`
- Create: `src/potner_exploration/potner_exploration/exploration_node.py`
- Modify: `src/potner_bringup/package.xml`
- Test: `tests/test_slam_exploration_config.py`

**Interfaces:**
- Consumes: Task 2 `find_frontiers`, Task 3 `ExplorationController`
- Produces: console script `exploration = potner_exploration.exploration_node:main`
- Consumes ROS: `/map`, `/odom`, `/navigate_to_pose`, `/compute_path_to_pose`, `/map_saver/save_map`, `/slam_toolbox/serialize_map`
- Produces ROS: `/cmd_vel_nav` zero Twist on stop, navigation goals, map files

- [ ] **Step 1: 패키지·노드 계약 실패 테스트 작성**

```python
def test_exploration_package_declares_required_ros_dependencies():
    root = ET.parse(EXPLORATION_PACKAGE).getroot()
    deps = {node.text for node in root.findall("exec_depend")}
    assert {"rclpy", "geometry_msgs", "nav_msgs", "nav2_msgs", "slam_toolbox"} <= deps

def test_exploration_console_script_exists():
    assert "exploration = potner_exploration.exploration_node:main" in EXPLORATION_SETUP.read_text()
```

- [ ] **Step 2: 실패 확인 후 ament_python 골격 구현**

Run: `python -m pytest tests/test_slam_exploration_config.py -v`
Expected: 패키지가 없어 FAIL

- [ ] **Step 3: 노드 파라미터와 구독 구현**

OccupancyGrid를 `GridMap`으로 변환한다. `/odom`은 최신성만 감시하고 실제
지도 좌표의 로봇 자세는 `map -> base_footprint` TF에서 읽는다. map/odom
마지막 수신 시간을 노드 clock으로 기록하고 transient local map QoS를 쓴다.

- [ ] **Step 4: 경로 검사와 이동 액션 구현**

timer에서 상태 머신과 후보 목록을 평가한다. 상위 후보부터
`ComputePathToPose`를 비동기로 호출하고 빈 path이면 다음 후보를 검사한다.
유효 path이면 같은 pose를 `NavigateToPose`에 보내고 결과 콜백에서 성공 또는
실패 전이를 호출한다. 동시에 하나의 path 또는 navigation 요청만 유지한다.

- [ ] **Step 5: 취소·정지·저장 구현**

상태 머신 Decision에 따라 현재 goal handle을 취소하고 zero Twist를 5회
발행한다. 완료 시 timestamp가 붙은 절대 output base를 만들고 SaveMap의
`map_url`, `image_format="pgm"`, `map_mode="trinary"`, free/occupied threshold를
설정한다. SerializePoseGraph에는 동일 base에 `_posegraph`를 붙인 filename을
전달한다. 두 future가 모두 성공한 경우에만 COMPLETED 처리한다.

- [ ] **Step 6: 문법과 정적 계약 확인**

Run: `python -m compileall -q src/potner_exploration`
Run: `python -m pytest tests/test_slam_exploration_config.py -v`
Expected: PASS

---

### Task 5: Nav2 설정과 원클릭 탐색 launch

**Files:**
- Create: `src/potner_bringup/config/exploration.yaml`
- Modify: `src/potner_bringup/config/nav2_params.yaml`
- Create: `src/potner_bringup/launch/navigation_core.launch.py`
- Create: `src/potner_bringup/launch/exploration.launch.py`
- Test: `tests/test_slam_exploration_config.py`

**Interfaces:**
- Consumes: Task 4 console script and parameters
- Produces: `ros2 launch potner_bringup exploration.launch.py map_output_dir:=...`

- [ ] **Step 1: 설정·launch 계약 실패 테스트 작성**

YAML에서 선속도 `<=0.12`, 각속도 `<=0.5`, 양의 clearance/timeout, 필수 Nav2
plugin 목록을 검사한다. launch 소스에서 `navigation_core.launch.py`,
`async_slam_toolbox_node`, `map_saver_server`, lifecycle manager,
`potner_exploration`이 있고 속도 smoother와 AMCL이 없는지 검사한다.

- [ ] **Step 2: 실패 확인**

Run: `python -m pytest tests/test_slam_exploration_config.py -v`
Expected: 탐색 YAML/launch가 없어 FAIL

- [ ] **Step 3: exploration.yaml 작성**

node 이름 `exploration` 아래에 frontier, clearance, scoring, timeout, blacklist,
map output 파라미터를 모두 수치로 정의한다. 코드 선언 기본값과 YAML 값의
일치를 정적 테스트에 포함한다.

- [ ] **Step 4: nav2_params.yaml을 실행 가능한 구성으로 완성**

BT navigator, controller server(DWB), planner server(NavFn), behavior server,
local/global costmap(static/obstacle/inflation), waypoint follower,
velocity smoother의 plugin과 필수 파라미터를 명시한다. controller의
`max_vel_x=0.12`, `max_vel_theta=0.5`; costmap의 `robot_radius=0.14`,
`inflation_radius=0.20`; `/scan` observation source를 사용한다.

- [ ] **Step 5: exploration.launch.py 구현**

`robot.launch.py`, slam_toolbox, 공용 `navigation_core.launch.py`, map saver
lifecycle node, exploration node를 구성한다. 공용 launch에서 controller의
`cmd_vel`을 `cmd_vel_nav`로 보낸다. `use_rviz`, `map_output_dir`, `map_name` 인자를
노드 파라미터에 전달한다. AMCL과 map server는 포함하지 않는다.

- [ ] **Step 6: 설정 테스트 확인**

Run: `python -m pytest tests/test_slam_exploration_config.py tests/test_config_consistency.py -v`
Expected: PASS

---

### Task 6: 운영 문서와 전체 회귀 검증

**Files:**
- Create: `docs/AUTONOMOUS_SLAM.md`
- Modify: `README.md`
- Test: all tests

**Interfaces:**
- Produces: 설치, 보정, 실행, 모니터링, 저장 결과, 장애 대응 문서

- [ ] **Step 1: 문서 계약 테스트 추가**

`docs/AUTONOMOUS_SLAM.md`에 원클릭 launch, `/scan`, `/odom`, TF 확인,
wheel_separation 보정, 저장 파일 3종, 비상 정지, 실패 로그 확인 명령이 모두
포함되는지 정적 검사한다.

- [ ] **Step 2: 운영 문서 작성**

다음 실제 명령을 포함한다.

```bash
ros2 launch potner_bringup exploration.launch.py \
  map_output_dir:=$HOME/potner_ws/maps map_name:=home use_rviz:=true
ros2 topic hz /scan
ros2 topic hz /odom
ros2 run tf2_ros tf2_echo map base_footprint
```

첫 주행은 통제된 공간에서 수행하고 물리 비상 정지 수단을 준비하며, 생성된
YAML/PGM/posegraph 파일을 확인하는 절차를 기술한다.

- [ ] **Step 3: 관련 테스트 실행**

Run: `python -m pytest tests/test_frontier_detector.py tests/test_exploration_state.py tests/test_slam_exploration_config.py tests/test_kinematics.py tests/test_config_consistency.py -v`
Expected: PASS

- [ ] **Step 4: 전체 테스트와 lint 실행**

Run: `python -m pytest tests/ -q`
Expected: 모든 테스트 PASS

Run: `python -m flake8 . --count --select=E9,F63,F7,F82 --exclude=venv,.git,__pycache__,build,install,log,.pio`
Expected: 오류 0개

- [ ] **Step 5: 작업 트리 확인**

Run: `git diff --check && git status --short`
Expected: whitespace 오류 없음. 설계, 계획, 구현, 테스트, 문서 파일만 수정됨.
