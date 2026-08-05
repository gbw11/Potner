from potner_exploration.map_revision import MapRevisionGate


def test_new_gate_requires_an_initial_check():
    gate = MapRevisionGate()

    assert gate.needs_check


def test_checked_map_is_not_checked_again_until_map_changes():
    gate = MapRevisionGate()
    gate.mark_checked()

    assert not gate.needs_check

    gate.mark_map()

    assert gate.needs_check


def test_reset_requires_rechecking_the_current_map():
    gate = MapRevisionGate()
    gate.mark_checked()
    gate.reset()

    assert gate.needs_check


def test_revision_is_exposed_for_async_result_matching():
    gate = MapRevisionGate()
    gate.mark_map()
    gate.mark_map()

    assert gate.revision == 2


def test_only_result_from_current_revision_is_accepted():
    gate = MapRevisionGate()
    gate.mark_map()
    gate.mark_map()

    assert gate.is_current(2)
    assert not gate.is_current(1)


def test_stale_nonempty_analysis_is_accepted_only_as_a_goal_proposal():
    gate = MapRevisionGate()
    gate.mark_map()
    gate.mark_map()

    assert gate.accepts_analysis(revision=1, has_frontiers=True)
    assert not gate.accepts_analysis(revision=1, has_frontiers=False)
    assert gate.accepts_analysis(revision=2, has_frontiers=False)
