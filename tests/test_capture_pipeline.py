"""스트림 B — 촬영 로그 기록 + 이미지 파일명 생성 규칙."""

from __future__ import annotations

import json
import logging
from datetime import datetime
from pathlib import Path

from src.events.store import EventStore
from src.mqtt.capture_command import CaptureCommandListener, build_capture_listener
from src.vision import naming
from src.vision.base import CaptureResult
from src.vision.mock import MockCamera
from src.vision.snapshot import next_capture_path
from src.vision.store import CameraStore, capture_event

# --- 파일명 규칙 ---------------------------------------------------------


def test_build_filename_uses_frame_prefix_and_stamp():
    stamp = naming.timestamp_stamp(datetime(2026, 8, 3, 14, 30, 5))
    assert stamp == "20260803_143005"
    assert naming.build_filename(stamp) == "frame_20260803_143005.jpg"
    assert naming.build_filename(stamp, seq=2) == "frame_20260803_143005_2.jpg"
    assert naming.build_filename(stamp, ext="png") == "frame_20260803_143005.png"
    assert naming.build_filename(stamp, ext=".png") == "frame_20260803_143005.png"


def test_normalize_ext_handles_missing_dot_and_none():
    assert naming.normalize_ext("jpg") == ".jpg"
    assert naming.normalize_ext(".png") == ".png"
    assert naming.normalize_ext(None) == ".jpg"
    assert naming.normalize_ext("") == ".jpg"


def test_next_available_path_adds_suffix_instead_of_overwriting(tmp_path):
    fixed = datetime(2026, 8, 3, 14, 30, 5)
    first = naming.next_available_path(tmp_path, now=fixed)
    assert first.name == "frame_20260803_143005.jpg"
    first.touch()
    second = naming.next_available_path(tmp_path, now=fixed)
    assert second.name == "frame_20260803_143005_1.jpg"
    second.touch()
    assert naming.next_available_path(tmp_path, now=fixed).name == "frame_20260803_143005_2.jpg"


def test_store_and_snapshot_share_one_naming_rule(tmp_path):
    """예전에는 store 는 frame_, snapshot 은 snap_ 로 서로 달랐다."""
    store_name = CameraStore(tmp_path).next_path().name
    snapshot_name = next_capture_path(tmp_path).name

    assert store_name.startswith("frame_")
    assert snapshot_name.startswith("frame_")
    assert not snapshot_name.startswith("snap_")
    # 접두사·확장자·스탬프 자릿수가 완전히 같은 형식이어야 한다
    assert naming.is_capture_filename(store_name)
    assert naming.is_capture_filename(snapshot_name)


def test_snapshot_and_store_do_not_collide_in_same_directory(tmp_path):
    """같은 폴더를 쓰는 두 진입점이 서로의 파일을 덮어쓰지 않는다."""
    store = CameraStore(tmp_path)
    first = store.next_path()
    first.touch()
    second = next_capture_path(tmp_path)
    assert second != first
    second.touch()
    assert store.next_path() not in {first, second}


def test_store_next_path_respects_extension(tmp_path):
    assert CameraStore(tmp_path).next_path(ext=".png").suffix == ".png"


def test_is_capture_filename_rejects_other_names():
    assert naming.is_capture_filename("frame_20260803_143005.jpg")
    assert naming.is_capture_filename("frame_20260803_143005_7.png")
    assert not naming.is_capture_filename("snap_20260803_143005.jpg")
    assert not naming.is_capture_filename("frame_2026_143005.jpg")
    assert not naming.is_capture_filename("index.jsonl")


def test_mock_capture_file_follows_naming_rule(tmp_path):
    store = CameraStore(tmp_path)
    result = store.capture(MockCamera())
    assert result.ok
    assert naming.is_capture_filename(result.path)
    assert result.path.endswith(".png")


# --- 촬영 이벤트 로그 ----------------------------------------------------


def _ok_result(path: str = "data/camera/frame_20260803_143005.jpg") -> CaptureResult:
    return CaptureResult(
        timestamp="2026-08-03T14:30:05+09:00",
        path=path,
        width=1920,
        height=1080,
        driver="picamera2",
        ok=True,
    )


def test_capture_event_records_time_outcome_and_trigger():
    event = capture_event(_ok_result(), trigger="mqtt", request_id="req-1")
    assert event["type"] == "capture"
    assert event["ok"] is True
    assert event["trigger"] == "mqtt"
    assert event["request_id"] == "req-1"
    assert event["timestamp"] == "2026-08-03T14:30:05+09:00"
    assert event["file_name"] == "frame_20260803_143005.jpg"
    assert event["width"] == 1920
    assert event["driver"] == "picamera2"
    assert "error" not in event


def test_capture_event_records_failure_reason():
    failed = CaptureResult(
        timestamp="2026-08-03T14:30:05+09:00",
        path="data/camera/frame_x.jpg",
        width=0,
        height=0,
        driver="picamera2",
        ok=False,
        error="camera not connected",
    )
    event = capture_event(failed, trigger="collector", code="CAPTURE_FAILED")
    assert event["ok"] is False
    assert event["error"] == "camera not connected"
    assert event["code"] == "CAPTURE_FAILED"
    assert "실패" in event["message"]


def test_capture_event_without_result_still_logs():
    event = capture_event(None, trigger="mqtt", request_id=7, error="Camera disabled")
    assert event["ok"] is False
    assert event["error"] == "Camera disabled"
    assert event["request_id"] == 7
    assert event["timestamp"]


def test_store_capture_appends_event_and_index(tmp_path):
    events = EventStore(tmp_path / "events.jsonl")
    store = CameraStore(tmp_path / "camera", event_store=events, trigger="collector")
    result = store.capture(MockCamera())

    rows = events.recent()
    assert len(rows) == 1
    event = rows[0]
    assert event["type"] == "capture"
    assert event["trigger"] == "collector"
    assert event["ok"] is True
    assert event["file_name"] == Path(result.path).name

    # 인덱스(index.jsonl)는 이벤트 로그와 별개로 그대로 유지된다
    index_rows = store.recent()
    assert len(index_rows) == 1
    assert index_rows[0]["path"] == result.path


def test_store_capture_without_event_store_still_works(tmp_path):
    store = CameraStore(tmp_path)
    assert store.capture(MockCamera()).ok
    assert len(store.recent()) == 1


def test_store_capture_logs_event_when_camera_raises(tmp_path):
    class BoomCamera:
        def capture(self, dest_path: str) -> CaptureResult:
            raise RuntimeError("Camera disabled")

        def close(self) -> None:
            return None

    events = EventStore(tmp_path / "events.jsonl")
    store = CameraStore(tmp_path / "camera", event_store=events)
    try:
        store.capture(BoomCamera())
    except RuntimeError:
        pass
    else:
        raise AssertionError("예외가 그대로 올라와야 한다")

    rows = events.recent()
    assert len(rows) == 1
    assert rows[0]["ok"] is False
    assert "Camera disabled" in rows[0]["error"]
    # 실패한 촬영은 이미지 인덱스에는 남지 않는다
    assert store.recent() == []


def test_broken_event_store_does_not_break_capture(tmp_path):
    class BrokenStore:
        def append(self, event):
            raise OSError("disk full")

    store = CameraStore(tmp_path, event_store=BrokenStore())
    assert store.capture(MockCamera()).ok


# --- MQTT 리스너 로그 ----------------------------------------------------


def _listener(capture_fn=None, event_store=None) -> CaptureCommandListener:
    return CaptureCommandListener(
        capture_fn=capture_fn or (lambda: _ok_result()),
        host="localhost",
        device_id="pi-test",
        username="u",
        password="p",
        command_topic="t/cmd",
        result_topic="t/result",
        event_store=event_store,
    )


def test_mqtt_capture_success_is_logged_as_event(tmp_path):
    events = EventStore(tmp_path / "events.jsonl")
    result = _listener(event_store=events).handle_command({"requestId": "req-1"})
    assert result["status"] == "OK"

    rows = events.recent()
    assert len(rows) == 1
    assert rows[0]["trigger"] == "mqtt"
    assert rows[0]["request_id"] == "req-1"
    assert rows[0]["ok"] is True
    assert rows[0]["file_name"] == "frame_20260803_143005.jpg"


def test_mqtt_capture_failure_and_exception_are_logged_as_events(tmp_path):
    failed = CaptureResult(
        timestamp="2026-08-03T14:30:05+09:00",
        path="data/camera/frame_x.jpg",
        width=0,
        height=0,
        driver="picamera2",
        ok=False,
        error="camera not connected",
    )
    events = EventStore(tmp_path / "events.jsonl")
    assert _listener(lambda: failed, events).handle_command({"requestId": "r1"})["code"] == (
        "CAPTURE_FAILED"
    )

    def boom():
        raise RuntimeError("Camera disabled")

    assert _listener(boom, events).handle_command({"requestId": "r2"})["code"] == "CAMERA_ERROR"

    rows = events.recent()
    assert [row["code"] for row in rows] == ["CAPTURE_FAILED", "CAMERA_ERROR"]
    assert all(row["ok"] is False and row["trigger"] == "mqtt" for row in rows)


def test_invalid_command_is_not_recorded_as_a_capture(tmp_path):
    """페이로드 오류는 촬영 시도가 아니므로 촬영 이력에 남기지 않는다."""
    events = EventStore(tmp_path / "events.jsonl")
    listener = _listener(event_store=events)
    assert listener.handle_raw_command("{not json")["code"] == "INVALID_PAYLOAD"
    assert listener.handle_command({})["code"] == "INVALID_REQUEST"
    assert events.recent() == []


def test_listener_uses_logging_not_print(caplog, capsys):
    caplog.set_level(logging.INFO, logger="src.mqtt.capture_command")
    _listener().handle_command({"requestId": "req-log"})

    assert capsys.readouterr().out == ""
    messages = [record.message for record in caplog.records]
    assert any("촬영 명령 수신" in message for message in messages)
    assert any("촬영 성공" in message for message in messages)


def test_listener_error_paths_log_at_warning_or_error(caplog):
    caplog.set_level(logging.DEBUG, logger="src.mqtt.capture_command")

    def boom():
        raise RuntimeError("Camera disabled")

    _listener(boom).handle_command({"requestId": "req-err"})
    levels = {record.levelno for record in caplog.records if "Camera disabled" in record.message}
    assert levels and min(levels) >= logging.WARNING


def test_busy_command_logs_warning(caplog):
    caplog.set_level(logging.INFO, logger="src.mqtt.capture_command")
    listener = _listener()
    assert listener._capture_lock.acquire(blocking=False)
    try:
        assert listener.handle_command({"requestId": "req-busy"})["status"] == "BUSY"
    finally:
        listener._capture_lock.release()
    assert any(
        record.levelno == logging.WARNING and "무시" in record.message
        for record in caplog.records
    )


def test_build_capture_listener_wires_event_store(tmp_path, monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)

    events_path = tmp_path / "events.jsonl"
    config = {
        "mqtt": {
            "enabled": True,
            "host": "broker.example",
            "device_id": "raspberry-01",
            "username": "raspberry-01",
            "password": "secret",
        },
        "camera": {"enabled": True},
        "events": {"path": str(events_path)},
    }
    listener = build_capture_listener(config, capture_fn=lambda: _ok_result())
    assert listener is not None
    assert listener.event_store is not None

    listener.handle_command({"requestId": "req-wired"})
    lines = [line for line in events_path.read_text(encoding="utf-8").splitlines() if line]
    assert len(lines) == 1
    assert json.loads(lines[0])["type"] == "capture"


def test_build_capture_listener_without_events_section_has_no_store(monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)

    config = {
        "mqtt": {
            "enabled": True,
            "host": "broker.example",
            "device_id": "raspberry-01",
            "username": "raspberry-01",
            "password": "secret",
        },
        "camera": {"enabled": True},
    }
    listener = build_capture_listener(config, capture_fn=lambda: _ok_result())
    assert listener is not None
    assert listener.event_store is None
