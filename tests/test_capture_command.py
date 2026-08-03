from __future__ import annotations

from src.mqtt.capture_command import CaptureCommandListener, build_capture_listener
from src.vision.base import CaptureResult
from src.vision.mock import MockCamera
from src.vision.store import CameraStore


def _ok_result(path: str = "data/camera/frame_20260730_120000.jpg") -> CaptureResult:
    return CaptureResult(
        timestamp="2026-07-30T12:00:00+09:00",
        path=path,
        width=1920,
        height=1080,
        driver="picamera2",
        ok=True,
    )


def _listener(capture_fn=None) -> CaptureCommandListener:
    return CaptureCommandListener(
        capture_fn=capture_fn or (lambda: _ok_result()),
        host="localhost",
        device_id="pi-test",
        username="u",
        password="p",
        command_topic="t/cmd",
        result_topic="t/result",
    )


def test_handle_command_captures_and_returns_ok():
    result = _listener().handle_command({"requestId": "req-1"})
    assert result["status"] == "OK"
    assert result["requestId"] == "req-1"
    assert result["deviceId"] == "pi-test"
    assert result["fileName"] == "frame_20260730_120000.jpg"
    assert result["path"].endswith("frame_20260730_120000.jpg")
    assert result["width"] == 1920
    assert result["height"] == 1080
    assert result["driver"] == "picamera2"
    assert result["capturedAt"] == "2026-07-30T12:00:00+09:00"
    assert result["messageId"]


def test_handle_command_rejects_missing_or_invalid_request_id():
    calls: list[int] = []

    def capture():
        calls.append(1)
        return _ok_result()

    listener = _listener(capture)
    for payload in ({}, {"requestId": None}, {"requestId": ""}, {"requestId": [1]}):
        result = listener.handle_command(payload)
        assert result["status"] == "ERROR"
        assert result["code"] == "INVALID_REQUEST"
    assert calls == []


def test_handle_raw_command_rejects_broken_json():
    listener = _listener()
    for raw in ("{not json", '"just a string"', "[1,2]"):
        result = listener.handle_raw_command(raw)
        assert result["status"] == "ERROR"
        assert result["code"] == "INVALID_PAYLOAD"


def test_handle_raw_command_valid_json_passes_through():
    result = _listener().handle_raw_command('{"requestId": "req-9"}')
    assert result["status"] == "OK"
    assert result["requestId"] == "req-9"


def test_capture_failure_returns_error_code():
    failed = CaptureResult(
        timestamp="2026-07-30T12:00:00+09:00",
        path="data/camera/frame_x.jpg",
        width=1920,
        height=1080,
        driver="picamera2",
        ok=False,
        error="camera not connected",
    )
    result = _listener(lambda: failed).handle_command({"requestId": "req-2"})
    assert result["status"] == "ERROR"
    assert result["code"] == "CAPTURE_FAILED"
    assert "camera not connected" in result["error"]
    assert result["requestId"] == "req-2"


def test_capture_exception_returns_camera_error():
    def boom():
        raise RuntimeError("Camera disabled")

    result = _listener(boom).handle_command({"requestId": "req-3"})
    assert result["status"] == "ERROR"
    assert result["code"] == "CAMERA_ERROR"
    assert "Camera disabled" in result["error"]


def test_handle_command_busy_when_lock_held():
    listener = _listener()
    assert listener._capture_lock.acquire(blocking=False)
    try:
        result = listener.handle_command({"requestId": "req-4"})
        assert result["status"] == "BUSY"
        assert result["requestId"] == "req-4"
    finally:
        listener._capture_lock.release()
    # 락 해제 후에는 정상 처리
    assert listener.handle_command({"requestId": "req-5"})["status"] == "OK"


def test_capture_with_mock_camera_saves_image(tmp_path):
    store = CameraStore(tmp_path)
    camera = MockCamera()
    listener = _listener(lambda: store.capture(camera))
    result = listener.handle_command({"requestId": "req-6"})
    assert result["status"] == "OK"
    saved = tmp_path / result["fileName"]
    assert saved.exists()
    assert saved.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"


def test_store_next_path_never_overwrites(tmp_path):
    store = CameraStore(tmp_path)
    first = store.next_path()
    first.touch()
    second = store.next_path()
    assert first != second
    second.touch()
    assert store.next_path() not in {first, second}


def test_build_capture_listener_requires_mqtt_and_camera(monkeypatch):
    monkeypatch.delenv("MQTT_PASSWORD", raising=False)
    monkeypatch.delenv("MQTT_USERNAME", raising=False)
    monkeypatch.delenv("DEVICE_ID", raising=False)
    capture = lambda: _ok_result()  # noqa: E731

    base = {
        "mqtt": {
            "enabled": True,
            "host": "broker.example",
            "device_id": "raspberry-01",
            "username": "raspberry-01",
            "password": "secret",
        },
        "camera": {"enabled": True},
    }
    listener = build_capture_listener(base, capture_fn=capture)
    assert listener is not None
    assert listener.command_topic == "potner/device/raspberry-01/command/capture"
    # 서버 규약(55b35be)은 result/capture. yaml 에 키가 없는 config/default.yaml 경로가
    # 이 폴백을 그대로 쓰므로, 폴백이 규약과 어긋나면 결과가 엉뚱한 토픽으로 나간다.
    assert listener.result_topic == "potner/device/raspberry-01/result/capture"
    assert listener.device_id == "raspberry-01"

    no_mqtt = {**base, "mqtt": {**base["mqtt"], "enabled": False}}
    assert build_capture_listener(no_mqtt, capture_fn=capture) is None

    no_camera = {**base, "camera": {"enabled": False}}
    assert build_capture_listener(no_camera, capture_fn=capture) is None
