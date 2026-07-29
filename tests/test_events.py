from __future__ import annotations

from src.events.detector import EventDetector
from src.events.store import EventStore
from src.status.rules import evaluate_status


def test_first_update_emits_session_start(tmp_path, plant_status):
    store = EventStore(tmp_path / "events.jsonl")
    detector = EventDetector(store)

    emitted = detector.update(plant_status)

    assert len(emitted) == 1
    assert emitted[0]["type"] == "session_start"
    assert len(store.recent()) == 1


def test_level_change_and_attention(tmp_path, normal_reading, dry_soil_reading, default_status_rules):
    store = EventStore(tmp_path / "events.jsonl")
    detector = EventDetector(store)

    detector.update(evaluate_status(normal_reading, default_status_rules))
    emitted = detector.update(evaluate_status(dry_soil_reading, default_status_rules))

    types = [e["type"] for e in emitted]
    assert "level_change" in types
    assert "attention" in types


def test_unchanged_status_emits_nothing(tmp_path, plant_status):
    store = EventStore(tmp_path / "events.jsonl")
    detector = EventDetector(store)

    detector.update(plant_status)
    emitted = detector.update(plant_status)

    assert emitted == []


def test_event_store_skips_corrupt_lines(tmp_path):
    path = tmp_path / "events.jsonl"
    path.write_text(
        '{"timestamp": "a", "type": "ok"}\n'
        "not-json\n"
        '{"timestamp": "b", "type": "ok2"}\n',
        encoding="utf-8",
    )
    store = EventStore(path)

    rows = store.recent(limit=10)
    assert len(rows) == 2
    assert rows[-1]["type"] == "ok2"
