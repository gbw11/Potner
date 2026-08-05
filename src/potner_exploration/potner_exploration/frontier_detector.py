"""ROS에 의존하지 않는 OccupancyGrid frontier 검출기."""

import math
from collections import deque
from dataclasses import dataclass
from typing import Callable, Sequence


Cell = tuple[int, int]
Point = tuple[float, float]
CancelCheck = Callable[[], bool]


class FrontierAnalysisCancelled(RuntimeError):
    pass


@dataclass(frozen=True)
class GridMap:
    """행 우선 OccupancyGrid 데이터와 월드 좌표 메타데이터."""

    width: int
    height: int
    resolution: float
    origin_x: float
    origin_y: float
    data: tuple[int, ...]

    def __post_init__(self):
        if self.width <= 0 or self.height <= 0:
            raise ValueError("width and height must be positive")
        if self.resolution <= 0.0:
            raise ValueError("resolution must be positive")
        if len(self.data) != self.width * self.height:
            raise ValueError("data length does not match width * height")
        object.__setattr__(self, "data", tuple(self.data))

    def value(self, cell: Cell) -> int:
        col, row = cell
        return self.data[row * self.width + col]

    def contains(self, cell: Cell) -> bool:
        col, row = cell
        return 0 <= col < self.width and 0 <= row < self.height


@dataclass(frozen=True)
class FrontierConfig:
    """frontier 검출과 목표 점수 정책."""

    free_threshold: int = 20
    occupied_threshold: int = 65
    min_cluster_size: int = 5
    clearance_m: float = 0.20
    information_gain_weight: float = 1.0
    distance_weight: float = 1.0
    blocked_radius_m: float = 0.35

    def __post_init__(self):
        if not 0 <= self.free_threshold < self.occupied_threshold <= 100:
            raise ValueError("occupancy thresholds are invalid")
        if self.min_cluster_size <= 0:
            raise ValueError("min_cluster_size must be positive")
        if self.clearance_m < 0.0 or self.blocked_radius_m < 0.0:
            raise ValueError("distance limits must not be negative")


@dataclass(frozen=True)
class Frontier:
    """하나의 frontier 군집과 Nav2에 전달할 자유 셀 목표."""

    cells: tuple[Cell, ...]
    goal_cell: Cell
    goal_x: float
    goal_y: float
    size_m: float
    score: float


@dataclass(frozen=True)
class FrontierAnalysis:
    """한 지도에서 frontier와 장애물 거리장을 한 번에 계산한 결과."""

    frontiers: tuple[Frontier, ...]
    obstacle_distances: dict[Cell, float]


def cell_to_world(grid: GridMap, col: int, row: int) -> Point:
    """셀 중심을 월드 좌표로 변환합니다."""
    if not grid.contains((col, row)):
        raise ValueError("cell is outside the grid")
    return (
        grid.origin_x + (col + 0.5) * grid.resolution,
        grid.origin_y + (row + 0.5) * grid.resolution,
    )


def world_to_cell(grid: GridMap, x: float, y: float) -> Cell | None:
    """월드 좌표가 속한 셀을 반환하고 지도 밖이면 None을 반환합니다."""
    col = math.floor((x - grid.origin_x) / grid.resolution)
    row = math.floor((y - grid.origin_y) / grid.resolution)
    cell = (col, row)
    return cell if grid.contains(cell) else None


def target_remains_safe_frontier(
    grid: GridMap,
    target_x: float,
    target_y: float,
    frontiers: Sequence[Frontier],
    config: FrontierConfig,
    tolerance_m: float,
    obstacle_distances: dict[Cell, float] | None = None,
) -> bool:
    """Return whether a navigation target is still safe and near a frontier."""
    if tolerance_m < 0.0:
        raise ValueError("tolerance_m must not be negative")
    target_cell = world_to_cell(grid, target_x, target_y)
    if target_cell is None or not _is_free(grid.value(target_cell), config):
        return False
    distances = (
        obstacle_distances
        if obstacle_distances is not None
        else _obstacle_distances(grid, config)
    )
    if distances[target_cell] < config.clearance_m:
        return False
    for frontier in frontiers:
        for frontier_cell in frontier.cells:
            cell_x, cell_y = cell_to_world(grid, *frontier_cell)
            if math.hypot(cell_x - target_x, cell_y - target_y) <= tolerance_m:
                return True
    return False


def target_remains_local_frontier(
    grid: GridMap,
    target_x: float,
    target_y: float,
    config: FrontierConfig,
    tolerance_m: float,
) -> bool:
    """전체 맵 분석 없이 목표 주변에 안전한 frontier가 남았는지 확인합니다."""
    if tolerance_m < 0.0:
        raise ValueError("tolerance_m must not be negative")
    target_cell = world_to_cell(grid, target_x, target_y)
    if target_cell is None or not _is_free(grid.value(target_cell), config):
        return False
    if not _cell_has_clearance(grid, target_cell, config):
        return False

    radius_cells = math.ceil(tolerance_m / grid.resolution)
    target_col, target_row = target_cell
    for row in range(target_row - radius_cells, target_row + radius_cells + 1):
        for col in range(
            target_col - radius_cells,
            target_col + radius_cells + 1,
        ):
            cell = (col, row)
            if not grid.contains(cell) or not _is_free(
                grid.value(cell), config
            ):
                continue
            cell_x, cell_y = cell_to_world(grid, col, row)
            if math.hypot(cell_x - target_x, cell_y - target_y) > tolerance_m:
                continue
            if _frontier_cluster_reaches_minimum(grid, cell, config):
                return True
    return False


def find_frontiers(
    grid: GridMap,
    robot_x: float,
    robot_y: float,
    config: FrontierConfig,
    blocked_points: Sequence[Point] = (),
) -> list[Frontier]:
    """로봇이 도달 가능한 frontier를 높은 점수 순으로 반환합니다."""
    return list(
        analyze_frontiers(
            grid,
            robot_x,
            robot_y,
            config,
            blocked_points,
        ).frontiers
    )


def analyze_frontiers(
    grid: GridMap,
    robot_x: float,
    robot_y: float,
    config: FrontierConfig,
    blocked_points: Sequence[Point] = (),
    should_cancel: CancelCheck | None = None,
) -> FrontierAnalysis:
    """frontier와 목표 안전성 검사에 재사용할 장애물 거리장을 반환합니다."""
    _raise_if_cancelled(should_cancel)
    obstacle_distance = _obstacle_distances(grid, config, should_cancel)
    robot_cell = world_to_cell(grid, robot_x, robot_y)
    if robot_cell is None or not _is_free(grid.value(robot_cell), config):
        return FrontierAnalysis((), obstacle_distance)

    reachable = _reachable_free_cells(
        grid, robot_cell, config, should_cancel
    )
    frontier_cells = set()
    for cell in reachable:
        _raise_if_cancelled(should_cancel)
        if (
            _has_unknown_neighbor(grid, cell)
            and obstacle_distance[cell] >= config.clearance_m
        ):
            frontier_cells.add(cell)

    result = []
    for cluster in _cluster_cells(frontier_cells, should_cancel):
        _raise_if_cancelled(should_cancel)
        if len(cluster) < config.min_cluster_size:
            continue

        goal_cell = _goal_near_centroid(cluster)
        goal_x, goal_y = cell_to_world(grid, *goal_cell)
        if _is_blocked(
            goal_x,
            goal_y,
            blocked_points,
            config.blocked_radius_m,
        ):
            continue

        distance = math.hypot(goal_x - robot_x, goal_y - robot_y)
        size_m = len(cluster) * grid.resolution
        score = (
            config.information_gain_weight * size_m
            - config.distance_weight * distance
        )
        result.append(
            Frontier(
                cells=tuple(
                    sorted(cluster, key=lambda cell: (cell[1], cell[0]))
                ),
                goal_cell=goal_cell,
                goal_x=goal_x,
                goal_y=goal_y,
                size_m=size_m,
                score=score,
            )
        )

    return FrontierAnalysis(
        frontiers=tuple(
            sorted(
                result,
                key=lambda frontier: (
                    -frontier.score,
                    math.hypot(
                        frontier.goal_x - robot_x,
                        frontier.goal_y - robot_y,
                    ),
                    frontier.goal_cell[1],
                    frontier.goal_cell[0],
                ),
            )
        ),
        obstacle_distances=obstacle_distance,
    )


def _is_free(value: int, config: FrontierConfig) -> bool:
    return 0 <= value <= config.free_threshold


def _reachable_free_cells(
    grid: GridMap,
    start: Cell,
    config: FrontierConfig,
    should_cancel: CancelCheck | None = None,
) -> set[Cell]:
    visited = {start}
    queue = deque([start])
    while queue:
        _raise_if_cancelled(should_cancel)
        current = queue.popleft()
        for neighbor in _neighbors4(grid, current):
            if neighbor in visited or not _is_free(
                grid.value(neighbor), config
            ):
                continue
            visited.add(neighbor)
            queue.append(neighbor)
    return visited


def _has_unknown_neighbor(grid: GridMap, cell: Cell) -> bool:
    return any(
        grid.value(neighbor) == -1 for neighbor in _neighbors4(grid, cell)
    )


def _cluster_cells(
    cells: set[Cell],
    should_cancel: CancelCheck | None = None,
) -> list[set[Cell]]:
    remaining = set(cells)
    clusters = []
    while remaining:
        _raise_if_cancelled(should_cancel)
        start = min(remaining, key=lambda cell: (cell[1], cell[0]))
        remaining.remove(start)
        cluster = {start}
        queue = deque([start])
        while queue:
            _raise_if_cancelled(should_cancel)
            col, row = queue.popleft()
            for dc, dr in _OFFSETS_8:
                neighbor = (col + dc, row + dr)
                if neighbor not in remaining:
                    continue
                remaining.remove(neighbor)
                cluster.add(neighbor)
                queue.append(neighbor)
        clusters.append(cluster)
    return clusters


def _goal_near_centroid(cluster: set[Cell]) -> Cell:
    mean_col = sum(cell[0] for cell in cluster) / len(cluster)
    mean_row = sum(cell[1] for cell in cluster) / len(cluster)
    return min(
        cluster,
        key=lambda cell: (
            (cell[0] - mean_col) ** 2 + (cell[1] - mean_row) ** 2,
            cell[1],
            cell[0],
        ),
    )


def _is_blocked(
    x: float,
    y: float,
    blocked_points: Sequence[Point],
    radius: float,
) -> bool:
    return any(
        math.hypot(x - px, y - py) <= radius
        for px, py in blocked_points
    )


def _cell_has_clearance(
    grid: GridMap,
    cell: Cell,
    config: FrontierConfig,
) -> bool:
    if config.clearance_m <= 0.0:
        return True
    radius_cells = math.ceil(config.clearance_m / grid.resolution)
    col, row = cell
    for candidate_row in range(row - radius_cells, row + radius_cells + 1):
        for candidate_col in range(
            col - radius_cells,
            col + radius_cells + 1,
        ):
            candidate = (candidate_col, candidate_row)
            if not grid.contains(candidate):
                continue
            if grid.value(candidate) < config.occupied_threshold:
                continue
            distance = math.hypot(
                candidate_col - col,
                candidate_row - row,
            ) * grid.resolution
            if distance < config.clearance_m:
                return False
    return True


def _frontier_cluster_reaches_minimum(
    grid: GridMap,
    start: Cell,
    config: FrontierConfig,
) -> bool:
    if not _is_safe_frontier_cell(grid, start, config):
        return False
    visited = {start}
    queue = deque([start])
    while queue:
        if len(visited) >= config.min_cluster_size:
            return True
        col, row = queue.popleft()
        for dc, dr in _OFFSETS_8:
            neighbor = (col + dc, row + dr)
            if neighbor in visited:
                continue
            if not _is_safe_frontier_cell(grid, neighbor, config):
                continue
            visited.add(neighbor)
            queue.append(neighbor)
    return len(visited) >= config.min_cluster_size


def _is_safe_frontier_cell(
    grid: GridMap,
    cell: Cell,
    config: FrontierConfig,
) -> bool:
    return (
        grid.contains(cell)
        and _is_free(grid.value(cell), config)
        and _has_unknown_neighbor(grid, cell)
        and _cell_has_clearance(grid, cell, config)
    )


def _obstacle_distances(
    grid: GridMap,
    config: FrontierConfig,
    should_cancel: CancelCheck | None = None,
) -> dict[Cell, float]:
    """안전거리 판정에 필요한 반경 안에서만 정확한 거리를 계산합니다."""
    distances = {
        (col, row): math.inf
        for row in range(grid.height)
        for col in range(grid.width)
    }
    _raise_if_cancelled(should_cancel)
    radius_cells = math.ceil(config.clearance_m / grid.resolution)
    local_offsets = []
    for dr in range(-radius_cells, radius_cells + 1):
        for dc in range(-radius_cells, radius_cells + 1):
            distance = math.hypot(dc, dr) * grid.resolution
            if distance < config.clearance_m:
                local_offsets.append((dc, dr, distance))

    for row in range(grid.height):
        _raise_if_cancelled(should_cancel)
        for col in range(grid.width):
            if grid.value((col, row)) < config.occupied_threshold:
                continue
            distances[(col, row)] = 0.0
            for dc, dr, distance in local_offsets:
                neighbor = (col + dc, row + dr)
                if grid.contains(neighbor) and distance < distances[neighbor]:
                    distances[neighbor] = distance
    return distances


def _raise_if_cancelled(should_cancel: CancelCheck | None):
    if should_cancel is not None and should_cancel():
        raise FrontierAnalysisCancelled()


def _neighbors4(grid: GridMap, cell: Cell):
    col, row = cell
    for dc, dr in _OFFSETS_4:
        neighbor = (col + dc, row + dr)
        if grid.contains(neighbor):
            yield neighbor


_OFFSETS_4 = ((1, 0), (-1, 0), (0, 1), (0, -1))
_OFFSETS_8 = (
    (-1, -1),
    (0, -1),
    (1, -1),
    (-1, 0),
    (1, 0),
    (-1, 1),
    (0, 1),
    (1, 1),
)
