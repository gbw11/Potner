"""Pure classification helpers for asynchronous Nav2 action results."""

from enum import Enum, auto


class PathResultKind(Enum):
    VALID = auto()
    UNREACHABLE = auto()
    SYSTEM_FAILURE = auto()


def classify_path_result(
    status: int,
    pose_count: int,
    *,
    succeeded_status: int,
    aborted_status: int,
) -> PathResultKind:
    """Separate an unreachable candidate from an action infrastructure fault."""
    if status == aborted_status:
        return PathResultKind.UNREACHABLE
    if status == succeeded_status:
        if pose_count > 0:
            return PathResultKind.VALID
        return PathResultKind.UNREACHABLE
    return PathResultKind.SYSTEM_FAILURE
