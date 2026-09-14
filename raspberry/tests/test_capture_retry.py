"""촬영 재시도 로직 (src/vision/capture_flow.py + capture_command 배선).

세 시나리오를 축으로 검증한다:
  1. 정상 촬영                — 1회 시도로 끝, 대기 없음
  2. 일시적 오류 후 재시도 성공 — 카메라 오류 / 촬영 실패 / 저장 실패 / 밝기 탈락 각각
  3. 지속적 오류로 재시도 실패 — 횟수 소진 후 실패 회신 + ERROR 로그

`sleep_fn` 을 주입하므로 이 파일의 어떤 테스트도 실제로 대기하지 않는다.
"""

from __future__ import annotations

import logging
import struct
import zlib

import pytest

from src.config import load_config
from src.mqtt.capture_command import CaptureCommandListener, build_capture_listener
from src.transport.uploader import UploadResult
from src.vision.base import CaptureResult
from src.vision.capture_flow import (
    CameraUnavailableError,
    RetryPolicy,
    capture_with_retry,
    run_attempt,
)
from src.vision.quality import BrightnessThresholds

FLOW_LOGGER = "src.vision.capture_flow"


# --- 헬퍼 ----------------------------------------------------------------


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def solid_png(value: int, width: int = 8, height: int = 6) -> bytes:
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
    """실제 파일까지 만들어 두는 성공 CaptureResult."""
    path = tmp_path / name
    path.write_bytes(solid_png(value))
    return CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path=str(path),
        width=8,
        height=6,
        driver="mock",
        ok=True,
    )


def failed_capture(error: str = "camera not connected") -> CaptureResult:
    return CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path="",
        width=0,
        height=0,
        driver="mock",
        ok=False,
        error=error,
    )


class Script:
    """호출될 때마다 미리 정해둔 값을 돌려주는 촬영 함수 대역.

    항목이 예외 인스턴스면 raise, 아니면 반환. 목록이 끝나면 마지막 항목을 반복한다.
    """

    def __init__(self, *steps) -> None:
        self.steps = list(steps)
        self.calls = 0

    def __call__(self):
        step = self.steps[min(self.calls, len(self.steps) - 1)]
        self.calls += 1
        if isinstance(step, BaseException):
            raise step
        return step


class Sleeper:
    """sleep_fn 대역 — 실제로 자지 않고 요청된 대기 시간만 기록한다."""

    def __init__(self) -> None:
        self.delays: list[float] = []

    def __call__(self, seconds: float) -> None:
        self.delays.append(seconds)


class RecordingUploader:
    def __init__(self, result: UploadResult | None = None) -> None:
        self.result = result or UploadResult(ok=True, status_code=201, message="created")
        self.calls: list[tuple] = []

    def upload_capture(self, capture, *, request_id, extra=None):
        self.calls.append((capture.path, request_id))
        return self.result


@pytest.fixture
def strict_quality() -> BrightnessThresholds:
    # 전수 검사로 고정 — 샘플링 때문에 판정이 흔들리지 않게
    return BrightnessThresholds(sample_step=1)


@pytest.fixture
def fast_policy() -> RetryPolicy:
    return RetryPolicy(max_attempts=3, delay_sec=0.5, backoff=True, backoff_max_sec=8.0)


def make_listener(**kwargs) -> CaptureCommandListener:
    defaults = dict(
        capture_fn=lambda: None,
        host="127.0.0.1",
        command_topic="t/cmd",
        result_topic="t/res",
        device_id="raspberry-01",
        username="u",
        password="p",
    )
    defaults.update(kwargs)
    return CaptureCommandListener(**defaults)


# --- 정책 (config) --------------------------------------------------------


def test_policy_reads_camera_retry_section():
    policy = RetryPolicy.from_config(
        {
            "camera": {
                "retry": {
                    "enabled": True,
                    "max_attempts": 5,
                    "delay_sec": 0.25,
                    "backoff": False,
                    "retry_on_quality": False,
                    "verify_saved_file": False,
                    "discard_failed_images": True,
                }
            }
        }
    )
    assert policy.enabled is True
    assert policy.attempts == 5
    assert policy.delay_sec == 0.25
    assert policy.retry_on_quality is False
    assert policy.verify_saved_file is False
    assert policy.discard_failed_images is True


def test_policy_without_retry_section_keeps_single_attempt():
    """구버전 yaml(재시도 섹션 없음)에서는 동작이 바뀌면 안 된다."""
    policy = RetryPolicy.from_config({"camera": {"enabled": True}})
    assert policy.enabled is False
    assert policy.attempts == 1
    assert policy.verify_saved_file is False
    assert RetryPolicy.from_config(None).attempts == 1
    assert RetryPolicy.from_config({}).attempts == 1


def test_policy_disabled_flag_forces_one_attempt():
    policy = RetryPolicy.from_config({"camera": {"retry": {"enabled": False, "max_attempts": 9}}})
    assert policy.attempts == 1


def test_policy_backoff_and_fixed_delay():
    backoff = RetryPolicy(delay_sec=1.0, backoff=True, backoff_max_sec=8.0)
    assert [backoff.delay_for(n) for n in (1, 2, 3, 4, 5)] == [1.0, 2.0, 4.0, 8.0, 8.0]

    fixed = RetryPolicy(delay_sec=1.5, backoff=False)
    assert [fixed.delay_for(n) for n in (1, 2, 3)] == [1.5, 1.5, 1.5]

    assert RetryPolicy(delay_sec=0).delay_for(1) == 0.0


def test_shipped_yaml_files_define_retry_policy():
    """정책은 코드가 아니라 yaml 이 갖는다 — 두 config 모두에 있어야 한다."""
    for path in ("config/default.yaml", "config/raspberry_pi.yaml"):
        section = load_config(path)["camera"]["retry"]
        assert section["enabled"] is True
        assert section["max_attempts"] >= 2
        assert section["delay_sec"] > 0
        policy = RetryPolicy.from_config(load_config(path))
        assert policy.attempts == section["max_attempts"]


# --- 시나리오 1: 정상 촬영 -------------------------------------------------


def test_normal_capture_succeeds_on_first_attempt(tmp_path, strict_quality, fast_policy):
    capture = Script(write_capture(tmp_path, "ok.png", 128))
    sleeper = Sleeper()

    attempt = capture_with_retry(
        capture, policy=fast_policy, quality=strict_quality, sleep_fn=sleeper
    )

    assert attempt.ok is True
    assert attempt.attempts == 1
    assert attempt.retried is False
    assert capture.calls == 1
    assert sleeper.delays == []           # 성공하면 대기하지 않는다
    assert attempt.report.verdict == "ok"


def test_listener_normal_capture_reports_single_attempt(tmp_path, strict_quality, fast_policy):
    capture = write_capture(tmp_path, "ok.png", 128)
    uploader = RecordingUploader()
    sleeper = Sleeper()
    listener = make_listener(
        capture_fn=lambda: capture,
        quality=strict_quality,
        uploader=uploader,
        retry=fast_policy,
        sleep_fn=sleeper,
    )

    result = listener.handle_command({"requestId": "r-ok"})

    assert result["status"] == "OK"
    assert result["attempts"] == 1
    assert result["uploaded"] is True
    assert uploader.calls == [(capture.path, "r-ok")]
    assert sleeper.delays == []


# --- 시나리오 2: 일시적 오류 후 재시도 성공 --------------------------------


def test_transient_camera_exception_then_success(tmp_path, strict_quality, fast_policy, caplog):
    caplog.set_level(logging.INFO, logger=FLOW_LOGGER)
    capture = Script(RuntimeError("i2c timeout"), write_capture(tmp_path, "ok.png", 128))
    sleeper = Sleeper()

    attempt = capture_with_retry(
        capture,
        policy=fast_policy,
        quality=strict_quality,
        sleep_fn=sleeper,
        request_id="r-1",
    )

    assert attempt.ok is True
    assert attempt.attempts == 2
    assert capture.calls == 2
    assert sleeper.delays == [0.5]        # 1회 실패 후 1번만 대기
    assert [outcome.code for outcome in attempt.history] == ["CAMERA_ERROR", None]

    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert any("촬영 재시도 1/3" in r.message and "i2c timeout" in r.message for r in warnings)
    assert any(
        r.levelno == logging.INFO and "2/3회차 시도에서 성공" in r.message
        for r in caplog.records
    )


def test_capture_failed_result_then_success(tmp_path, fast_policy):
    capture = Script(failed_capture(), write_capture(tmp_path, "ok.png", 120))
    sleeper = Sleeper()

    attempt = capture_with_retry(capture, policy=fast_policy, sleep_fn=sleeper)

    assert attempt.ok is True
    assert attempt.attempts == 2
    assert attempt.history[0].code == "CAPTURE_FAILED"


def test_missing_saved_file_is_retried(tmp_path, fast_policy):
    """촬영은 성공했다는데 파일이 없거나 0바이트면 저장 실패로 보고 다시 찍는다."""
    empty = tmp_path / "empty.png"
    empty.write_bytes(b"")
    zero_byte = CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path=str(empty),
        width=8,
        height=6,
        driver="mock",
        ok=True,
    )
    ghost = CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path=str(tmp_path / "gone.png"),
        width=8,
        height=6,
        driver="mock",
        ok=True,
    )
    capture = Script(ghost, zero_byte, write_capture(tmp_path, "ok.png", 100))
    sleeper = Sleeper()

    attempt = capture_with_retry(capture, policy=fast_policy, sleep_fn=sleeper)

    assert attempt.ok is True
    assert attempt.attempts == 3
    assert [outcome.code for outcome in attempt.history[:2]] == [
        "IMAGE_SAVE_FAILED",
        "IMAGE_SAVE_FAILED",
    ]
    assert sleeper.delays == [0.5, 1.0]   # 지수 백오프


def test_oserror_while_capturing_is_treated_as_save_failure(tmp_path, fast_policy):
    capture = Script(OSError("No space left on device"), write_capture(tmp_path, "ok.png", 100))

    attempt = capture_with_retry(capture, policy=fast_policy, sleep_fn=Sleeper())

    assert attempt.ok is True
    assert attempt.history[0].code == "IMAGE_SAVE_FAILED"


def test_dark_frame_is_retried_until_brightness_passes(tmp_path, strict_quality, fast_policy):
    capture = Script(
        write_capture(tmp_path, "dark.png", 5),
        write_capture(tmp_path, "bright.png", 250),
        write_capture(tmp_path, "ok.png", 128),
    )
    sleeper = Sleeper()

    attempt = capture_with_retry(
        capture, policy=fast_policy, quality=strict_quality, sleep_fn=sleeper
    )

    assert attempt.ok is True
    assert attempt.attempts == 3
    assert [outcome.code for outcome in attempt.history[:2]] == [
        "IMAGE_TOO_DARK",
        "IMAGE_TOO_BRIGHT",
    ]
    assert attempt.report.verdict == "ok"
    assert len(sleeper.delays) == 2


def test_listener_retry_success_still_uploads_and_logs_events(
    tmp_path, strict_quality, fast_policy
):
    """재시도로 성공한 뒤에도 후속 처리(업로드·이벤트 기록)가 정상 수행된다."""

    class MemoryEvents:
        def __init__(self) -> None:
            self.rows: list[dict] = []

        def append(self, event) -> None:
            self.rows.append(event)

    good = write_capture(tmp_path, "ok.png", 128)
    capture = Script(RuntimeError("driver busy"), write_capture(tmp_path, "dark.png", 4), good)
    uploader = RecordingUploader()
    events = MemoryEvents()
    sleeper = Sleeper()
    listener = make_listener(
        capture_fn=capture,
        quality=strict_quality,
        uploader=uploader,
        retry=fast_policy,
        sleep_fn=sleeper,
        event_store=events,
    )

    result = listener.handle_command({"requestId": "r-retry"})

    assert result["status"] == "OK"
    assert result["attempts"] == 3
    assert result["path"] == good.path
    assert result["quality"]["verdict"] == "ok"
    # 업로드는 통과한 마지막 이미지 1장에 대해서만 일어난다
    assert uploader.calls == [(good.path, "r-retry")]
    # 촬영 이력에는 시도 3건이 모두 남는다 (실패 원인 포함)
    assert len(events.rows) == 3
    assert events.rows[0]["ok"] is False
    assert events.rows[0]["code"] == "CAMERA_ERROR"
    assert "driver busy" in events.rows[0]["error"]
    assert events.rows[2]["ok"] is True


# --- 시나리오 3: 지속적 오류로 재시도 실패 ---------------------------------


def test_persistent_failure_exhausts_attempts(fast_policy, caplog):
    caplog.set_level(logging.INFO, logger=FLOW_LOGGER)
    capture = Script(RuntimeError("camera not connected"))
    sleeper = Sleeper()

    attempt = capture_with_retry(
        capture, policy=fast_policy, sleep_fn=sleeper, request_id="r-dead"
    )

    assert attempt.ok is False
    assert attempt.attempts == 3
    assert capture.calls == 3
    assert attempt.code == "CAMERA_ERROR"
    assert "camera not connected" in attempt.error
    assert attempt.retryable is True
    assert sleeper.delays == [0.5, 1.0]   # 마지막 실패 뒤에는 기다리지 않는다

    errors = [r for r in caplog.records if r.levelno == logging.ERROR]
    assert any("최종 실패" in r.message and "3회 시도" in r.message for r in errors)


def test_listener_persistent_dark_frames_return_error_with_attempts(
    tmp_path, strict_quality, fast_policy
):
    dark = write_capture(tmp_path, "dark.png", 3)
    uploader = RecordingUploader()
    listener = make_listener(
        capture_fn=lambda: dark,
        quality=strict_quality,
        uploader=uploader,
        retry=fast_policy,
        sleep_fn=Sleeper(),
    )

    result = listener.handle_command({"requestId": "r-dark"})

    assert result["status"] == "ERROR"
    assert result["code"] == "IMAGE_TOO_DARK"
    assert result["attempts"] == 3
    assert result["retryable"] is True
    assert result["path"] == dark.path
    assert result["quality"]["verdict"] == "too_dark"
    assert uploader.calls == []           # 탈락 이미지는 끝까지 업로드하지 않는다


def test_listener_persistent_capture_failure_returns_error(fast_policy):
    capture = Script(failed_capture("shutter jam"))
    listener = make_listener(capture_fn=capture, retry=fast_policy, sleep_fn=Sleeper())

    result = listener.handle_command({"requestId": "r-fail"})

    assert result["code"] == "CAPTURE_FAILED"
    assert result["attempts"] == 3
    assert "shutter jam" in result["error"]
    assert capture.calls == 3


# --- 재시도 대상이 아닌 실패 -----------------------------------------------


def test_camera_unavailable_is_not_retried(fast_policy, caplog):
    caplog.set_level(logging.INFO, logger=FLOW_LOGGER)
    capture = Script(CameraUnavailableError("Camera disabled"))
    sleeper = Sleeper()

    attempt = capture_with_retry(capture, policy=fast_policy, sleep_fn=sleeper)

    assert attempt.ok is False
    assert attempt.attempts == 1
    assert capture.calls == 1             # 설정 문제 — 다시 찍지 않는다
    assert attempt.retryable is False
    assert attempt.code == "CAMERA_ERROR"  # 서버 회신 코드는 기존 그대로
    assert sleeper.delays == []
    assert any(
        r.levelno == logging.ERROR and "재시도 대상이 아닌" in r.message
        for r in caplog.records
    )


def test_collector_signals_camera_unavailable_without_hardware():
    from src.collector import Collector

    collector = Collector({"platform": "mock", "camera": {"enabled": False}, "sensors": {}})
    try:
        with pytest.raises(CameraUnavailableError):
            collector.capture_once()
    finally:
        collector.close()


def test_invalid_request_never_starts_a_capture(fast_policy):
    capture = Script(failed_capture())
    listener = make_listener(capture_fn=capture, retry=fast_policy, sleep_fn=Sleeper())

    for payload in ({}, {"requestId": ""}, {"requestId": [1]}):
        assert listener.handle_command(payload)["code"] == "INVALID_REQUEST"
    assert listener.handle_raw_command("{not json")["code"] == "INVALID_PAYLOAD"
    assert capture.calls == 0


def test_quality_failure_not_retried_when_disabled(tmp_path, strict_quality):
    policy = RetryPolicy(max_attempts=3, delay_sec=0.1, retry_on_quality=False)
    capture = Script(write_capture(tmp_path, "dark.png", 5))

    attempt = capture_with_retry(
        capture, policy=policy, quality=strict_quality, sleep_fn=Sleeper()
    )

    assert attempt.ok is False
    assert attempt.attempts == 1
    assert attempt.code == "IMAGE_TOO_DARK"
    assert attempt.retryable is False


def test_warn_mode_image_is_not_a_failure(tmp_path):
    """on_failure: warn 이면 어두워도 통과 — 재시도 대상이 아니다."""
    capture = Script(write_capture(tmp_path, "dark.png", 5))
    quality = BrightnessThresholds(sample_step=1, reject_on_failure=False)

    attempt = capture_with_retry(
        capture, policy=RetryPolicy(max_attempts=3), quality=quality, sleep_fn=Sleeper()
    )

    assert attempt.ok is True
    assert attempt.attempts == 1
    assert attempt.report.verdict == "too_dark"


# --- 실패 이미지 파일 처리 --------------------------------------------------


def test_failed_images_are_kept_by_default(tmp_path, strict_quality, fast_policy):
    dark = write_capture(tmp_path, "dark.png", 5)
    capture = Script(dark, write_capture(tmp_path, "ok.png", 128))

    attempt = capture_with_retry(
        capture, policy=fast_policy, quality=strict_quality, sleep_fn=Sleeper()
    )

    assert attempt.ok is True
    assert attempt.discarded == ()
    assert (tmp_path / "dark.png").exists()   # 원인 분석용으로 남긴다


def test_discard_option_removes_intermediate_failures_only(tmp_path, strict_quality):
    policy = RetryPolicy(max_attempts=3, delay_sec=0.1, discard_failed_images=True)
    # 실제 촬영은 매번 새 파일을 만든다 (naming.py) — 시도마다 다른 경로를 준다
    capture = Script(
        write_capture(tmp_path, "dark1.png", 5),
        write_capture(tmp_path, "dark2.png", 4),
        write_capture(tmp_path, "dark3.png", 3),
    )

    attempt = capture_with_retry(
        capture, policy=policy, quality=strict_quality, sleep_fn=Sleeper()
    )

    assert attempt.ok is False
    assert attempt.attempts == 3
    # 재촬영으로 버려진 앞 2장은 삭제, 회신에 실리는 마지막 1장은 남긴다
    assert not (tmp_path / "dark1.png").exists()
    assert not (tmp_path / "dark2.png").exists()
    assert (tmp_path / "dark3.png").exists()
    assert len(attempt.discarded) == 2
    assert attempt.result.path.endswith("dark3.png")


# --- 락 / 동시성 -----------------------------------------------------------


def test_lock_is_held_across_retries_so_new_commands_get_busy(tmp_path, fast_policy):
    """재시도 중에도 촬영 락을 쥐고 있어야 BUSY 응답이 깨지지 않는다."""
    good = write_capture(tmp_path, "ok.png", 128)
    seen: list[str] = []

    def capture():
        # 재시도 도중(= 첫 시도 실패 직후 두 번째 시도 안)에 들어온 명령
        seen.append(listener.handle_command({"requestId": "r-second"})["status"])
        if len(seen) == 1:
            raise RuntimeError("transient")
        return good

    listener = make_listener(capture_fn=capture, retry=fast_policy, sleep_fn=Sleeper())

    result = listener.handle_command({"requestId": "r-first"})

    assert result["status"] == "OK"
    assert result["attempts"] == 2
    assert seen == ["BUSY", "BUSY"]


# --- 1회 시도 단위 (run_attempt) -------------------------------------------


def test_run_attempt_without_policy_skips_save_check(tmp_path):
    """정책 없이 부르면 기존 동작 — 저장 확인/밝기 검사 없이 촬영 결과만 본다."""
    ghost = CaptureResult(
        timestamp="2026-08-03T10:00:00+09:00",
        path=str(tmp_path / "missing.png"),
        width=8,
        height=6,
        driver="mock",
        ok=True,
    )
    outcome = run_attempt(lambda: ghost)
    assert outcome.ok is True


def test_run_attempt_handles_none_result():
    outcome = run_attempt(lambda: None, policy=RetryPolicy())
    assert outcome.ok is False
    assert outcome.code == "CAPTURE_FAILED"
    assert outcome.retryable is True


def test_on_attempt_failure_does_not_break_retry_loop(tmp_path, fast_policy):
    def broken(_outcome):
        raise OSError("disk full")

    capture = Script(RuntimeError("transient"), write_capture(tmp_path, "ok.png", 128))
    attempt = capture_with_retry(
        capture, policy=fast_policy, sleep_fn=Sleeper(), on_attempt=broken
    )
    assert attempt.ok is True
    assert attempt.attempts == 2


# --- 팩토리 배선 -----------------------------------------------------------


def test_build_capture_listener_wires_retry_policy():
    config = {
        "mqtt": {"enabled": True, "username": "raspberry-01", "password": "x"},
        "camera": {"enabled": True, "retry": {"max_attempts": 4, "delay_sec": 0.2}},
    }
    listener = build_capture_listener(config, capture_fn=lambda: None)

    assert listener is not None
    assert listener.retry is not None
    assert listener.retry.attempts == 4
    assert listener.retry.delay_sec == 0.2


def test_listener_without_retry_policy_keeps_single_attempt():
    capture = Script(failed_capture())
    listener = make_listener(capture_fn=capture)

    assert listener.handle_command({"requestId": "r-legacy"})["code"] == "CAPTURE_FAILED"
    assert capture.calls == 1
