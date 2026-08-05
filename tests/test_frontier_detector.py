"""OccupancyGrid 기반 frontier 검출의 순수 계산을 검증합니다."""

import math

import pytest

import potner_exploration.frontier_detector as detector
from potner_exploration.frontier_detector import (
    Frontier,
    FrontierConfig,
    GridMap,
    analyze_frontiers,
    cell_to_world,
    find_frontiers,
    target_remains_local_frontier,
    target_remains_safe_frontier,
    world_to_cell,
)


def grid(rows, resolution=1.0):
    return GridMap(
        width=len(rows[0]),
        height=len(rows),
        resolution=resolution,
        origin_x=0.0,
        origin_y=0.0,
        data=tuple(value for row in rows for value in row),
    )


def test_지도_크기와_데이터_길이가_다르면_거부한다():
    with pytest.raises(ValueError, match="data length"):
        GridMap(2, 2, 0.05, 0.0, 0.0, (0, 0, 0))


def test_0이하_해상도는_거부한다():
    with pytest.raises(ValueError, match="resolution"):
        GridMap(1, 1, 0.0, 0.0, 0.0, (0,))


def test_셀과_월드_좌표는_셀_중심을_기준으로_왕복한다():
    occupancy = GridMap(3, 2, 0.5, -1.0, 2.0, (0,) * 6)

    assert cell_to_world(occupancy, 1, 0) == pytest.approx((-0.25, 2.25))
    assert world_to_cell(occupancy, -0.25, 2.25) == (1, 0)
    assert world_to_cell(occupancy, 10.0, 10.0) is None


def test_미탐색_공간과_맞닿은_자유_셀을_하나의_frontier로_묶는다():
    occupancy = grid([
        [-1, -1, -1, -1, -1],
        [-1, 0, 0, 0, -1],
        [-1, 0, 0, 0, -1],
        [-1, 0, 0, 0, -1],
        [-1, -1, -1, -1, -1],
    ])

    frontiers = find_frontiers(
        occupancy,
        robot_x=2.5,
        robot_y=2.5,
        config=FrontierConfig(min_cluster_size=2, clearance_m=0.0),
    )

    assert len(frontiers) == 1
    assert len(frontiers[0].cells) == 8
    assert frontiers[0].goal_cell in frontiers[0].cells
    assert (frontiers[0].goal_x, frontiers[0].goal_y) == cell_to_world(
        occupancy, *frontiers[0].goal_cell
    )


def test_로봇과_연결되지_않은_자유_공간의_frontier는_제외한다():
    occupancy = grid([[0, 0, -1, 100, 0, 0, -1]])

    frontiers = find_frontiers(
        occupancy,
        robot_x=0.5,
        robot_y=0.5,
        config=FrontierConfig(min_cluster_size=1, clearance_m=0.0),
    )

    assert len(frontiers) == 1
    assert frontiers[0].cells == ((1, 0),)


def test_정보_이득_가중치가_크면_먼_대형_frontier를_먼저_고른다():
    occupancy = grid([
        [100, -1, 100, 100, 100, 100, -1, -1, -1, 100],
        [100, 0, 100, 100, 100, 100, 0, 0, 0, 100],
        [0, 0, 0, 0, 0, 0, 0, 0, 0, 100],
        [100, 100, 100, 100, 100, 100, 100, 100, 100, 100],
    ])

    frontiers = find_frontiers(
        occupancy,
        robot_x=0.5,
        robot_y=2.5,
        config=FrontierConfig(
            min_cluster_size=1,
            clearance_m=0.0,
            information_gain_weight=10.0,
            distance_weight=1.0,
        ),
    )

    assert len(frontiers[0].cells) == 3
    assert len(frontiers[1].cells) == 1


def test_거리_비용이_크면_가까운_frontier를_먼저_고른다():
    occupancy = grid([
        [100, -1, 100, 100, 100, 100, -1, -1, -1, 100],
        [100, 0, 100, 100, 100, 100, 0, 0, 0, 100],
        [0, 0, 0, 0, 0, 0, 0, 0, 0, 100],
        [100, 100, 100, 100, 100, 100, 100, 100, 100, 100],
    ])

    frontiers = find_frontiers(
        occupancy,
        robot_x=0.5,
        robot_y=2.5,
        config=FrontierConfig(
            min_cluster_size=1,
            clearance_m=0.0,
            information_gain_weight=1.0,
            distance_weight=10.0,
        ),
    )

    assert len(frontiers[0].cells) == 1
    assert len(frontiers[1].cells) == 3


def test_장애물_안전거리_안의_frontier는_제외한다():
    occupancy = grid([
        [-1, -1, -1, -1, -1],
        [-1, 0, 0, 0, -1],
        [-1, 0, 100, 0, -1],
        [-1, 0, 0, 0, -1],
        [-1, -1, -1, -1, -1],
    ])

    frontiers = find_frontiers(
        occupancy,
        robot_x=1.5,
        robot_y=2.5,
        config=FrontierConfig(
            min_cluster_size=1,
            clearance_m=1.5,
        ),
    )

    assert frontiers == []


def test_최근_실패_지점_주변의_frontier는_제외한다():
    occupancy = grid([[0, 0, -1]])

    frontiers = find_frontiers(
        occupancy,
        robot_x=0.5,
        robot_y=0.5,
        config=FrontierConfig(
            min_cluster_size=1,
            clearance_m=0.0,
            blocked_radius_m=0.5,
        ),
        blocked_points=((1.5, 0.5),),
    )

    assert frontiers == []


def test_navigation_target_must_still_be_free_even_near_a_frontier():
    occupancy = grid([
        [0, 0, -1],
        [0, 100, -1],
    ])
    adjacent_frontier = Frontier(
        cells=((0, 0),),
        goal_cell=(0, 0),
        goal_x=0.5,
        goal_y=0.5,
        size_m=1.0,
        score=1.0,
    )

    assert not target_remains_safe_frontier(
        occupancy,
        target_x=1.5,
        target_y=1.5,
        frontiers=(adjacent_frontier,),
        config=FrontierConfig(min_cluster_size=1, clearance_m=0.0),
        tolerance_m=2.0,
    )


def test_navigation_target_remains_valid_when_free_and_near_a_frontier():
    occupancy = grid([
        [0, 0, -1],
        [0, 0, -1],
    ])
    nearby_frontier = Frontier(
        cells=((1, 0),),
        goal_cell=(1, 0),
        goal_x=1.5,
        goal_y=0.5,
        size_m=1.0,
        score=1.0,
    )

    assert target_remains_safe_frontier(
        occupancy,
        target_x=1.5,
        target_y=1.5,
        frontiers=(nearby_frontier,),
        config=FrontierConfig(min_cluster_size=1, clearance_m=0.0),
        tolerance_m=1.1,
    )


def test_navigation_target_reuses_frontier_obstacle_distances(monkeypatch):
    occupancy = grid([
        [0, 0, -1],
        [0, 0, -1],
    ])
    config = FrontierConfig(min_cluster_size=1, clearance_m=0.0)
    analysis = analyze_frontiers(
        occupancy,
        robot_x=0.5,
        robot_y=0.5,
        config=config,
    )

    def unexpected_recalculation(*_args, **_kwargs):
        raise AssertionError("obstacle distances were recalculated")

    monkeypatch.setattr(
        detector,
        "_obstacle_distances",
        unexpected_recalculation,
    )

    assert target_remains_safe_frontier(
        occupancy,
        target_x=1.5,
        target_y=1.5,
        frontiers=analysis.frontiers,
        config=config,
        tolerance_m=1.1,
        obstacle_distances=analysis.obstacle_distances,
    )


def test_invalid_robot_cell_analysis_still_has_safe_target_distance_data():
    occupancy = grid([[100, 0, -1]])
    config = FrontierConfig(min_cluster_size=1, clearance_m=0.0)
    analysis = analyze_frontiers(
        occupancy,
        robot_x=0.5,
        robot_y=0.5,
        config=config,
    )

    assert not target_remains_safe_frontier(
        occupancy,
        target_x=1.5,
        target_y=0.5,
        frontiers=analysis.frontiers,
        config=config,
        tolerance_m=1.0,
        obstacle_distances=analysis.obstacle_distances,
    )


def test_obstacle_distances_stop_after_required_clearance_radius():
    occupancy = grid([[100, 0, 0, 0]])
    config = FrontierConfig(clearance_m=1.5)

    distances = detector._obstacle_distances(occupancy, config)

    assert distances[(0, 0)] == 0.0
    assert distances[(1, 0)] == pytest.approx(1.0)
    assert distances[(2, 0)] == math.inf


def test_local_navigation_check_accepts_a_safe_nearby_frontier():
    occupancy = grid([
        [0, 0, -1],
        [0, 0, -1],
    ])

    assert target_remains_local_frontier(
        occupancy,
        target_x=1.5,
        target_y=1.5,
        config=FrontierConfig(min_cluster_size=1, clearance_m=0.0),
        tolerance_m=1.1,
    )


def test_local_navigation_check_rejects_target_without_nearby_unknown_space():
    occupancy = grid([
        [0, 0, 0],
        [0, 0, 0],
    ])

    assert not target_remains_local_frontier(
        occupancy,
        target_x=1.5,
        target_y=1.5,
        config=FrontierConfig(min_cluster_size=1, clearance_m=0.0),
        tolerance_m=1.1,
    )


def test_local_navigation_check_rejects_target_inside_clearance_radius():
    occupancy = grid([
        [0, 0, -1],
        [0, 100, -1],
    ])

    assert not target_remains_local_frontier(
        occupancy,
        target_x=0.5,
        target_y=1.5,
        config=FrontierConfig(min_cluster_size=1, clearance_m=1.1),
        tolerance_m=1.1,
    )


def test_local_navigation_check_rejects_frontier_below_minimum_cluster_size():
    occupancy = grid([[0, 0, -1]])

    assert not target_remains_local_frontier(
        occupancy,
        target_x=1.5,
        target_y=0.5,
        config=FrontierConfig(min_cluster_size=5, clearance_m=0.0),
        tolerance_m=1.0,
    )
