"""촬영 → 밝기 검사 → 업로드 배선 통합 테스트.

각 스트림(B 촬영 파이프라인 / C 밝기 검사 / D 업로드)은 자기 모듈만 단위 테스트한다.
셋을 엮은 `CaptureCommandListener.handle_command` 흐름은 여기서만 검증한다.
"""

import struct
import zlib

import pytest

from src.mqtt.capture_command import CaptureCommandListener, build_capture_listener
from src.transport.uploader import UploadResult
from src.vision.base import CaptureResult
from src.vision.quality import BrightnessThresholds


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def solid_png(value: int, width: int = 16, height: int = 12) -> bytes:
    """모든 픽셀이 (v,v,v) 인 RGB PNG — 평균 luma 가 v 가 된다."""
    row = bytes([value, value, value]) * width
    raw = b"".join(b"\x00" + row for _ in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(raw, 6))
        + _chunk(b"IEND", b"")
    )


def write_capture(tmp_path, name: str, value: int) -> CaptureResult:
    path = tmp_path / name
    path.write_bytes(solid_png(value))
    return CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path=str(path),
        width=16,
        height=12,
        driver="mock",
        ok=True,
    )


class RecordingEventStore:
    """EventStore 대역 — 기록된 촬영 이벤트를 그대로 들고 있는다."""

    def __init__(self) -> None:
        self.events: list[dict] = []

    def append(self, event: dict) -> None:
        self.events.append(event)


class RecordingUploader:
    """ImageUploader 대역 — 호출 여부와 인자를 기록한다."""

    def __init__(self, result: UploadResult) -> None:
        self.result = result
        self.calls: list[tuple] = []

    def upload_capture(self, capture, *, request_id, extra=None):
        self.calls.append((capture.path, request_id))
        return self.result


def make_listener(**kwargs) -> CaptureCommandListener:
    defaults = dict(
        capture_fn=lambda: None,
        host="127.0.0.1",
        command_topic="t/cmd",
        result_topic="t/res",
        device_id="raspberry-01",
    )
    defaults.update(kwargs)
    return CaptureCommandListener(**defaults)


@pytest.fixture
def strict_quality() -> BrightnessThresholds:
    # 전수 검사로 고정 — 샘플링 때문에 판정이 흔들리지 않게
    return BrightnessThresholds(sample_step=1)


# --- 밝기 게이트 -----------------------------------------------------------


def test_dark_image_is_rejected_before_upload(tmp_path, strict_quality):
    capture = write_capture(tmp_path, "dark.png", 5)
    uploader = RecordingUploader(UploadResult(ok=True, status_code=200, message="ok"))
    listener = make_listener(
        capture_fn=lambda: capture, quality=strict_quality, uploader=uploader
    )

    result = listener.handle_command({"requestId": "r-1"})

    assert result["status"] == "ERROR"
    assert result["code"] == "IMAGE_TOO_DARK"
    assert result["retryable"] is True
    assert result["quality"]["verdict"] == "too_dark"
    # 탈락한 이미지는 업로드로 넘어가면 안 된다
    assert uploader.calls == []


def test_bright_image_is_rejected(tmp_path, strict_quality):
    capture = write_capture(tmp_path, "bright.png", 250)
    listener = make_listener(capture_fn=lambda: capture, quality=strict_quality)

    result = listener.handle_command({"requestId": "r-2"})

    assert result["code"] == "IMAGE_TOO_BRIGHT"
    assert result["path"] == capture.path


def test_normal_image_passes_gate_and_uploads(tmp_path, strict_quality):
    capture = write_capture(tmp_path, "ok.png", 128)
    uploader = RecordingUploader(UploadResult(ok=True, status_code=201, message="created"))
    listener = make_listener(
        capture_fn=lambda: capture, quality=strict_quality, uploader=uploader
    )

    result = listener.handle_command({"requestId": "r-3"})

    assert result["status"] == "OK"
    assert result["quality"]["verdict"] == "ok"
    assert result["uploaded"] is True
    assert uploader.calls == [(capture.path, "r-3")]


def test_undecodable_image_is_rejected_as_unknown(tmp_path, strict_quality):
    path = tmp_path / "broken.png"
    path.write_bytes(b"\x89PNG\r\n\x1a\nnot-a-real-png")
    capture = CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path=str(path),
        width=16,
        height=12,
        driver="mock",
        ok=True,
    )
    listener = make_listener(capture_fn=lambda: capture, quality=strict_quality)

    result = listener.handle_command({"requestId": "r-4"})

    assert result["code"] == "IMAGE_QUALITY_UNKNOWN"


def test_quality_disabled_keeps_previous_behaviour(tmp_path):
    capture = write_capture(tmp_path, "dark.png", 5)
    listener = make_listener(
        capture_fn=lambda: capture,
        quality=BrightnessThresholds(enabled=False, sample_step=1),
    )

    result = listener.handle_command({"requestId": "r-5"})

    # 검사가 꺼져 있으면 어두워도 통과하고 quality 키도 붙지 않는다
    assert result["status"] == "OK"
    assert "quality" not in result


def test_warn_mode_passes_dark_image_through(tmp_path):
    capture = write_capture(tmp_path, "dark.png", 5)
    listener = make_listener(
        capture_fn=lambda: capture,
        quality=BrightnessThresholds(sample_step=1, reject_on_failure=False),
    )

    result = listener.handle_command({"requestId": "r-6"})

    assert result["status"] == "OK"
    assert result["quality"]["verdict"] == "too_dark"


# --- 업로드 배선 -----------------------------------------------------------


def test_upload_failure_does_not_block_capture_result(tmp_path, strict_quality):
    capture = write_capture(tmp_path, "ok.png", 128)
    uploader = RecordingUploader(
        UploadResult(ok=False, status_code=503, message="서버 오류", code="HTTP_ERROR")
    )
    listener = make_listener(
        capture_fn=lambda: capture, quality=strict_quality, uploader=uploader
    )

    result = listener.handle_command({"requestId": "r-7"})

    # 업로드가 실패해도 촬영 결과 회신 자체는 성공으로 나간다
    assert result["status"] == "OK"
    assert result["uploaded"] is False
    assert result["uploadError"] == "서버 오류"


def test_disabled_uploader_adds_no_upload_keys(tmp_path, strict_quality):
    capture = write_capture(tmp_path, "ok.png", 128)
    uploader = RecordingUploader(
        UploadResult(ok=True, status_code=None, message="비활성", code="DISABLED")
    )
    listener = make_listener(
        capture_fn=lambda: capture, quality=strict_quality, uploader=uploader
    )

    result = listener.handle_command({"requestId": "r-8"})

    assert "uploaded" not in result
    assert "uploadError" not in result


def test_capture_failure_skips_both_gate_and_upload(tmp_path, strict_quality):
    failed = CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path="",
        width=0,
        height=0,
        driver="mock",
        ok=False,
        error="camera not connected",
    )
    uploader = RecordingUploader(UploadResult(ok=True, status_code=200, message="ok"))
    listener = make_listener(
        capture_fn=lambda: failed, quality=strict_quality, uploader=uploader
    )

    result = listener.handle_command({"requestId": "r-9"})

    assert result["code"] == "CAPTURE_FAILED"
    assert "quality" not in result
    assert uploader.calls == []


# --- 촬영 이력(events.jsonl) 정확성 ----------------------------------------


def test_quality_rejected_attempt_is_recorded_as_failure(tmp_path, strict_quality):
    """밝기 탈락은 셔터가 정상이었어도 이력에 '실패 + 사유'로 남아야 한다.

    이력만 보고 왜 재촬영됐는지 알 수 없으면 티켓의 "실패 시 오류 코드·원인 기록"
    조건을 못 채운다 (한때 ok=true '촬영 성공' 으로만 남던 회귀).
    """
    capture = write_capture(tmp_path, "dark.png", 5)
    events = RecordingEventStore()
    listener = make_listener(
        capture_fn=lambda: capture, quality=strict_quality, event_store=events
    )

    listener.handle_command({"requestId": "r-ev1"})

    assert len(events.events) == 1
    event = events.events[0]
    assert event["ok"] is False
    assert event["code"] == "IMAGE_TOO_DARK"
    assert "평균 밝기" in event["error"]
    assert "촬영 실패" in event["message"]
    assert event["request_id"] == "r-ev1"


def test_successful_attempt_is_recorded_without_error(tmp_path, strict_quality):
    capture = write_capture(tmp_path, "ok.png", 128)
    events = RecordingEventStore()
    listener = make_listener(
        capture_fn=lambda: capture, quality=strict_quality, event_store=events
    )

    listener.handle_command({"requestId": "r-ev2"})

    event = events.events[0]
    assert event["ok"] is True
    assert "code" not in event
    assert "error" not in event


def test_retry_history_records_every_attempt_with_its_reason(tmp_path, strict_quality):
    """재시도 3회 = 이력 3건. 실패분은 사유를 달고, 마지막 성공분만 ok=True."""
    from src.vision.capture_flow import RetryPolicy

    captures = [
        write_capture(tmp_path, "a.png", 5),
        write_capture(tmp_path, "b.png", 250),
        write_capture(tmp_path, "c.png", 128),
    ]
    events = RecordingEventStore()
    listener = make_listener(
        capture_fn=lambda: captures.pop(0),
        quality=strict_quality,
        event_store=events,
        retry=RetryPolicy(enabled=True, max_attempts=3, delay_sec=0.0, backoff=False),
        sleep_fn=lambda _s: None,
    )

    result = listener.handle_command({"requestId": "r-ev3"})

    assert result["status"] == "OK"
    assert result["attempts"] == 3
    assert [e["ok"] for e in events.events] == [False, False, True]
    assert [e.get("code") for e in events.events] == [
        "IMAGE_TOO_DARK",
        "IMAGE_TOO_BRIGHT",
        None,
    ]


# --- 팩토리 배선 -----------------------------------------------------------


def test_build_capture_listener_wires_quality_and_uploader():
    config = {
        "mqtt": {"enabled": True, "username": "raspberry-01", "password": "x"},
        "camera": {"enabled": True, "quality": {"enabled": True, "sample_step": 3}},
        "upload": {"enabled": False},
    }
    listener = build_capture_listener(config, capture_fn=lambda: None)

    assert listener is not None
    assert listener.quality is not None
    assert listener.quality.sample_step == 3
    assert listener.uploader is not None
    assert listener.uploader.enabled is False
