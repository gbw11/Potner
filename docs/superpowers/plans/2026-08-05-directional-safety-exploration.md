# Directional Safety Exploration Implementation Plan

> **For agentic workers:** Execute inline with test-first RED/GREEN cycles. Do not commit; the user explicitly prohibited commits.

**Goal:** Route every selected drive command through a fail-safe directional obstacle filter, preserve Nav2 avoidance and recovery motion, and reduce configured motion limits by 30%.

**Architecture:** `twist_mux` publishes `/cmd_vel_requested`; a ROS-independent policy classifies four LiDAR sectors and filters linear/angular components; `safety_node` owns sensor freshness, hysteresis, and final `/cmd_vel` publication. Frontier selection remains unchanged and Nav2 remains responsible for choosing avoidance and recovery directions.

**Tech Stack:** ROS 2 Humble, rclpy, geometry_msgs, sensor_msgs, twist_mux, Nav2 DWB/Behavior Server, pytest, YAML.

## Global Constraints

- Work only in the existing `SLAM` git worktree.
- Preserve all existing uncommitted autonomous exploration work.
- Do not create a git commit.
- A missing or stale scan must produce zero final velocity.
- Reverse is allowed only when the rear LiDAR sector has valid, clear measurements.
- New configured motion limits equal exactly 70% of their previous values.

---

### Task 1: ROS-independent directional safety policy

**Files:**
- Create: `src/potner_mission/potner_mission/safety_policy.py`
- Create: `tests/test_safety_policy.py`

**Interfaces:**
- `SectorClearance(front, rear, left, right)` stores nearest valid distance or infinity, plus valid sample counts.
- `SafetyConfig(stop_distance, bumper_stop_distance, hysteresis)` validates positive thresholds.
- `DirectionalSafety.update_scan(...)` updates independently hysteretic sector states.
- `DirectionalSafety.filter(linear_x, angular_z, bumper_left, bumper_right)` returns `SafetyResult(linear_x, angular_z, limited, reasons)`.

- [ ] Write tests for forward blocking, safe turn preservation, rear-gated reverse, side-gated rotation, ToF directionality, hysteresis, and missing-sector fail-safe.
- [ ] Run `python -m pytest tests/test_safety_policy.py -q` and verify failures are caused by the missing policy module.
- [ ] Implement the smallest policy that satisfies those behaviors without importing ROS.
- [ ] Re-run the focused tests and verify they pass.

### Task 2: ROS safety filter wiring

**Files:**
- Modify: `src/potner_mission/potner_mission/safety_node.py`
- Modify: `src/potner_bringup/config/potner_params.yaml`
- Modify: `tests/test_safety_source_contract.py`

**Interfaces:**
- Subscribe to `/cmd_vel_requested`, `/scan`, `bumper/left`, and `bumper/right`.
- Publish only validated commands to `/cmd_vel` at `publish_rate`.
- Publish `/safety/blocked` when the requested command is limited.
- Parameters: `rear_arc_deg`, `side_arc_deg`, `scan_timeout`, `command_timeout`, plus existing distances and hysteresis.

- [ ] Write source/config contract tests proving the node owns final `/cmd_vel`, has scan and command watchdogs, and no longer publishes `cmd_vel_safety`.
- [ ] Run the focused tests and verify the old source fails them.
- [ ] Refactor `safety_node` to cache timestamped inputs, classify LaserScan sectors through the policy, and publish fail-safe final commands.
- [ ] Add matching YAML/default parameters and re-run the focused tests.

### Task 3: Command path and Nav2 recovery configuration

**Files:**
- Modify: `src/potner_bringup/config/twist_mux.yaml`
- Modify: `src/potner_bringup/launch/robot.launch.py`
- Modify: `src/potner_bringup/config/nav2_params.yaml`
- Modify: `tests/test_slam_exploration_config.py`

**Interfaces:**
- `twist_mux/cmd_vel_out -> /cmd_vel_requested`.
- `safety_node -> /cmd_vel` is the only final software drive path.
- Nav2 DWB and Behavior Server continue publishing `/cmd_vel_nav` and retain backup/spin plugins.

- [ ] Extend configuration tests to reject a safety zero override topic, require the requested/final command split, require backup/spin, and require 0.30m costmap inflation.
- [ ] Run the focused tests and verify current mux/launch/config fail.
- [ ] Remove `cmd_vel_safety` from twist_mux, change the mux output remap, and tune inflation to 0.30m.
- [ ] Re-run focused configuration tests.

### Task 4: Thirty-percent motion reduction

**Files:**
- Modify: `src/potner_bringup/config/nav2_params.yaml`
- Modify: `src/potner_bringup/config/potner_params.yaml`
- Modify: `src/potner_base/potner_base/base_driver_node.py`
- Modify: `src/potner_base/potner_base/kinematics.py`
- Modify: `src/potner_docking/potner_docking/approach_controller.py`
- Modify: `tests/test_config_consistency.py`
- Modify: `tests/test_slam_exploration_config.py`

**Interfaces:**
- Nav2: `max_vel_x=max_speed_xy=0.084`, `max_vel_theta=0.35`, linear accel/decel magnitude `0.175`, angular accel/decel magnitude `0.56`.
- Base hard cap: `max_wheel_speed=0.175`.
- Docking: `approach_speed=0.084`, `max_angular=0.56`.

- [ ] Add exact-value assertions for every reduced limit.
- [ ] Run focused tests and verify old values fail.
- [ ] Update YAML and code defaults together.
- [ ] Re-run focused tests and existing kinematics/docking tests.

### Task 5: Documentation and regression verification

**Files:**
- Modify: `docs/AUTONOMOUS_SLAM.md`
- Modify: `README.md`

- [ ] Document the new command path, directional behavior, fail-safe rear-scan rule, reduced speed, and Jetson rebuild requirement.
- [ ] Run `python -m pytest tests/test_safety_policy.py tests/test_safety_source_contract.py tests/test_slam_exploration_config.py tests/test_config_consistency.py -q`.
- [ ] Run `python -m pytest tests/ -q`.
- [ ] Run `python -m compileall -q src/potner_mission src/potner_exploration src/potner_base src/potner_docking`.
- [ ] Inspect `git diff --check` and `git status --short`; keep all changes unstaged and uncommitted.
