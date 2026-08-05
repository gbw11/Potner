from potner_exploration.action_outcome import (
    PathResultKind,
    classify_path_result,
)


def test_nonempty_successful_path_is_valid():
    assert classify_path_result(4, 3, succeeded_status=4, aborted_status=6) is (
        PathResultKind.VALID
    )


def test_empty_successful_path_is_unreachable():
    assert classify_path_result(4, 0, succeeded_status=4, aborted_status=6) is (
        PathResultKind.UNREACHABLE
    )


def test_aborted_accepted_path_is_unreachable():
    assert classify_path_result(6, 0, succeeded_status=4, aborted_status=6) is (
        PathResultKind.UNREACHABLE
    )


def test_other_terminal_status_is_a_system_failure():
    assert classify_path_result(5, 0, succeeded_status=4, aborted_status=6) is (
        PathResultKind.SYSTEM_FAILURE
    )
