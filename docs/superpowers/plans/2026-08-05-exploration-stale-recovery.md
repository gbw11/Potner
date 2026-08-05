# Exploration Sensor Stale Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make autonomous exploration stop safely during transient sensor staleness, resume from a newly selected frontier when data recovers, and fail only after a continuous recovery timeout.

**Architecture:** Keep freshness evaluation and state transitions ROS-independent so they can be unit tested. Integrate the resulting status into the ROS node, cache navigation-target validation per map revision, and reuse frontier obstacle-distance analysis to reduce executor starvation.

**Performance amendment:** Independent review found that a synchronous full-map analysis can still exceed the odometry timeout on maps at or above 512×512 cells. Full frontier analysis therefore runs in `FrontierAnalysisWorker`, obstacle propagation is bounded by `clearance_m`, and NAVIGATING validation uses a local target window.

**Tech Stack:** Python 3, ROS 2 Humble `rclpy`, Nav2 actions, `pytest`, YAML.

## Global Constraints

- Do not create a git commit; the user explicitly requested uncommitted changes.
- Publish zero velocity and cancel the active Nav2 goal before waiting for sensor recovery.
- Recover within `sensor_recovery_timeout_s: 10.0`; otherwise transition to `FAILED`.
- Keep `map_timeout_s: 6.0`, `odom_timeout_s: 2.0`, and `tf_timeout_s: 1.0` unchanged.
- Do not modify ESP32 firmware, USB drivers, Nav2 behavior trees, or YDLIDAR/SLAM Toolbox packages.

---

### Task 1: Sensor freshness model

**Files:**
- Create: `src/potner_exploration/potner_exploration/sensor_freshness.py`
- Create: `tests/test_sensor_freshness.py`

**Interfaces:**
- Produces: `FreshnessLimits(map_timeout_s, odom_timeout_s, tf_timeout_s, future_tolerance_s=0.5)`.
- Produces: `FreshnessStatus(fresh, map_age_s, odom_age_s, tf_age_s, stale_sources)` and `describe()`.
- Produces: `evaluate_freshness(now, last_map_at, last_odom_at, last_tf_at, tf_ready, limits)`.

- [ ] **Step 1: Write failing tests for healthy data and each stale source**

```python
def test_evaluate_freshness_reports_odom_age_and_limit():
    status = evaluate_freshness(
        now=10.0,
        last_map_at=9.0,
        last_odom_at=7.9,
        last_tf_at=9.8,
        tf_ready=True,
        limits=FreshnessLimits(6.0, 2.0, 1.0),
    )
    assert not status.fresh
    assert status.stale_sources == ("odom",)
    assert "odom(age=2.10s limit=2.00s)" in status.describe()
```

Add separate assertions for missing map data, failed TF lookup, stale TF, and timestamps more than 0.5 seconds in the future.

- [ ] **Step 2: Run the new tests and verify RED**

Run: `python -m pytest tests/test_sensor_freshness.py -q`

Expected: collection fails because `potner_exploration.sensor_freshness` does not exist.

- [ ] **Step 3: Implement immutable freshness value objects and evaluation**

Use frozen dataclasses, validate positive limits, compute optional ages, and retain deterministic source order `map`, `odom`, `tf`. `describe()` must include the measured age and limit when available and `missing` or `lookup unavailable` otherwise.

- [ ] **Step 4: Run the new tests and verify GREEN**

Run: `python -m pytest tests/test_sensor_freshness.py -q`

Expected: all sensor freshness tests pass.

### Task 2: Recovery state transitions

**Files:**
- Modify: `src/potner_exploration/potner_exploration/exploration_state.py`
- Modify: `tests/test_exploration_state.py`

**Interfaces:**
- Extend: `ExplorationState.WAITING_FOR_SENSORS`.
- Extend: `ExplorationPolicy(sensor_recovery_timeout_s: float = 10.0)`.
- Preserve: `ExplorationController.tick(now: float, sensors_fresh: bool) -> Decision`.

- [ ] **Step 1: Replace the old immediate-failure test with recovery tests**

```python
def test_transient_sensor_stale_stops_and_waits_for_recovery():
    controller = ExplorationController(
        ExplorationPolicy(sensor_recovery_timeout_s=10.0)
    )
    controller.mark_ready(0.0)
    controller.goal_selected(1.0, (1.0, 0.0))

    decision = controller.tick(2.0, sensors_fresh=False)

    assert decision.state is ExplorationState.WAITING_FOR_SENSORS
    assert decision.cancel_goal
    assert decision.publish_stop


def test_sensor_recovery_selects_a_new_frontier():
    controller = ExplorationController(ExplorationPolicy())
    controller.mark_ready(0.0)
    controller.goal_selected(1.0, (1.0, 0.0))
    controller.tick(2.0, sensors_fresh=False)

    decision = controller.tick(5.0, sensors_fresh=True)

    assert decision.state is ExplorationState.SELECTING_FRONTIER
    assert controller.accepts_new_goal


def test_continuous_sensor_stale_eventually_fails():
    controller = ExplorationController(
        ExplorationPolicy(sensor_recovery_timeout_s=10.0)
    )
    controller.mark_ready(0.0)
    controller.tick(2.0, sensors_fresh=False)

    decision = controller.tick(12.0, sensors_fresh=False)

    assert decision.state is ExplorationState.FAILED
    assert decision.publish_stop
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `python -m pytest tests/test_exploration_state.py -q`

Expected: the first stale decision is `FAILED`, not `WAITING_FOR_SENSORS`.

- [ ] **Step 3: Implement the recovery state**

Record `_sensor_stale_started_at` on first stale input, clear the active goal, and return cancel/stop only on entry. While waiting, return `publish_stop=True`. On recovery, clear the stale timestamp and transition to `SELECTING_FRONTIER`. At elapsed time greater than or equal to the configured limit, call `_fail("sensor recovery timed out", cancel_goal=False)`.

- [ ] **Step 4: Run state tests and verify GREEN**

Run: `python -m pytest tests/test_exploration_state.py -q`

Expected: all exploration state tests pass.

### Task 3: Frontier analysis reuse and map-revision gating

**Files:**
- Modify: `src/potner_exploration/potner_exploration/frontier_detector.py`
- Modify: `tests/test_frontier_detector.py`
- Create: `src/potner_exploration/potner_exploration/map_revision.py`
- Create: `tests/test_map_revision.py`

**Interfaces:**
- Produces: `FrontierAnalysis(frontiers: tuple[Frontier, ...], obstacle_distances: dict[Cell, float])`.
- Produces: `analyze_frontiers(...) -> FrontierAnalysis`.
- Extend: `target_remains_safe_frontier(..., obstacle_distances=None)` so callers can reuse analysis.
- Produces: `MapRevisionGate.mark_map()`, `mark_checked()`, `needs_check`, and `reset()`.

- [ ] **Step 1: Write a failing obstacle-distance reuse test**

Create an analysis for a small map, pass `analysis.obstacle_distances` into `target_remains_safe_frontier`, and assert the result matches the existing API. Monkeypatch `_obstacle_distances` to raise after analysis creation; the reused call must not recompute it.

- [ ] **Step 2: Write failing map revision gate tests**

```python
def test_map_revision_gate_checks_each_map_once():
    gate = MapRevisionGate()
    assert gate.needs_check
    gate.mark_checked()
    assert not gate.needs_check
    gate.mark_map()
    assert gate.needs_check
```

- [ ] **Step 3: Run focused tests and verify RED**

Run: `python -m pytest tests/test_frontier_detector.py tests/test_map_revision.py -q`

Expected: `analyze_frontiers` and `MapRevisionGate` are missing.

- [ ] **Step 4: Implement reusable analysis without changing existing results**

Move the existing frontier calculation into `analyze_frontiers`, return the sorted tuple plus its distance map, and make `find_frontiers` return `list(analyze_frontiers(...).frontiers)` for compatibility. Use the supplied distance map in target validation when present.

- [ ] **Step 5: Implement the pure revision gate**

Track integer `revision` and `checked_revision`; `mark_map()` increments revision, `mark_checked()` copies it, `needs_check` compares them, and `reset()` sets `checked_revision` so the current map requires a new check.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run: `python -m pytest tests/test_frontier_detector.py tests/test_map_revision.py -q`

Expected: all tests pass and existing frontier ordering is unchanged.

### Task 4: ROS node integration and configuration

**Files:**
- Modify: `src/potner_exploration/potner_exploration/exploration_node.py`
- Modify: `src/potner_bringup/config/exploration.yaml`
- Modify: `tests/test_slam_exploration_config.py`
- Create: `tests/test_exploration_source_contract.py`

**Interfaces:**
- Consumes: `evaluate_freshness`, `FreshnessLimits`, `MapRevisionGate`, and `analyze_frontiers` from earlier tasks.
- Adds parameters: `sensor_recovery_timeout_s=10.0` and `sensor_stop_publish_period_s=0.5`.

- [ ] **Step 1: Write failing configuration and source-contract tests**

Assert both new YAML parameters are positive, recovery timeout is greater than all three freshness timeouts, `exploration_node.py` calls `evaluate_freshness`, and the NAVIGATING branch skips validation when `MapRevisionGate.needs_check` is false.

- [ ] **Step 2: Run integration-contract tests and verify RED**

Run: `python -m pytest tests/test_slam_exploration_config.py tests/test_exploration_source_contract.py -q`

Expected: new parameters and integration calls are absent.

- [ ] **Step 3: Integrate freshness status and diagnostic logging**

Construct `FreshnessLimits` from parameters, replace `_sensors_fresh` with `_sensor_status`, pass `.fresh` into the state machine, and log `status.describe()` with a two-second throttle while waiting. Keep the initial system-ready behavior unchanged.

- [ ] **Step 4: Integrate safe waiting behavior**

When the controller is `WAITING_FOR_SENSORS`, cancel path/navigation work on entry through the existing `Decision` handling and republish zero velocity according to `sensor_stop_publish_period_s`. Do not select or send goals until the controller returns to `SELECTING_FRONTIER`.

- [ ] **Step 5: Integrate revision-gated target validation**

Call `mark_map()` after accepting each valid `/map`. When sending a navigation goal, reset the gate. During NAVIGATING, return early if no new map requires a check; otherwise create one `FrontierAnalysis`, validate with its distance map, then mark the revision checked.

- [ ] **Step 6: Add YAML values and connect the policy**

Declare both parameters in the node, add them to `exploration.yaml`, and pass `sensor_recovery_timeout_s` into `ExplorationPolicy`.

- [ ] **Step 7: Run integration-contract tests and verify GREEN**

Run: `python -m pytest tests/test_slam_exploration_config.py tests/test_exploration_source_contract.py -q`

Expected: all tests pass.

### Task 5: Regression and deployment verification

**Files:**
- Modify only if tests reveal a defect in the files listed above.

**Interfaces:**
- No new interfaces.

- [ ] **Step 1: Run the complete ROS-independent suite**

Run: `python -m pytest tests/test_sensor_freshness.py tests/test_exploration_state.py tests/test_frontier_detector.py tests/test_map_revision.py tests/test_slam_exploration_config.py tests/test_exploration_source_contract.py -q`

Expected: all focused tests pass.

- [ ] **Step 2: Run all repository tests**

Run: `python -m pytest -q`

Expected: all tests pass with no new failures.

- [ ] **Step 3: Verify Python syntax**

Run: `python -m compileall -q src/potner_exploration/potner_exploration`

Expected: exit code 0.

- [ ] **Step 4: Review the uncommitted diff**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; all implementation and test changes remain uncommitted.

- [ ] **Step 5: Prepare Jetson rebuild and runtime checks**

Document the exact `colcon build --packages-select potner_exploration potner_bringup` command, environment sourcing, launch command, and expected `WAITING_FOR_SENSORS -> SELECTING_FRONTIER` recovery logs. Do not claim physical-runtime verification until the user runs it on the Jetson.
