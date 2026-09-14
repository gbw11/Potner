"""이미지 업로드 + 촬영 메타데이터 전송 테스트 (티켓 6·7).

실네트워크 금지 — urlopen monkeypatch 또는 127.0.0.1 로컬 http.server 스텁만 쓴다.
multipart 바디는 email 파서로 실제 파싱해서 파일 파트/메타데이터 파트를 확인한다.
"""

from __future__ import annotations

import io
import json
import logging
import threading
import urllib.error
import urllib.request
from email.parser import BytesParser
from email.policy import default as email_policy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Optional

import pytest

from src.config import load_config
from src.integrations.spring import MultipartPart, encode_multipart
from src.transport.uploader import (
    DEFAULT_FIELD_MAP,
    ImageUploader,
    build_image_uploader,
    redact,
)
from src.vision.base import CaptureResult

LOGGER_NAME = "src.transport.uploader"
PNG_BYTES = b"\x89PNG\r\n\x1a\n" + b"fake-image-body" * 4


# --------------------------------------------------------------------------
# 헬퍼
# --------------------------------------------------------------------------


def parse_multipart(body: bytes, content_type: str) -> dict[str, dict[str, Any]]:
    """multipart 바디 → {파트명: {filename, content_type, data}}."""
    raw = b"MIME-Version: 1.0\r\nContent-Type: " + content_type.encode("utf-8") + b"\r\n\r\n" + body
    message = BytesParser(policy=email_policy).parsebytes(raw)
    assert message.is_multipart(), "multipart 로 파싱되지 않았습니다"

    parsed: dict[str, dict[str, Any]] = {}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        parsed[str(name)] = {
            "filename": part.get_param("filename", header="content-disposition"),
            "content_type": part.get("Content-Type"),
            "data": part.get_payload(decode=True),
        }
    return parsed


class _FakeResponse:
    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *_exc: Any) -> bool:
        return False


class Recorder:
    """urlopen 대체. 요청을 기록하고 미리 정해둔 응답을 순서대로 돌려준다."""

    def __init__(self, responses: Optional[list[Any]] = None) -> None:
        # 각 항목: (status, body bytes) 또는 raise 할 Exception 인스턴스
        self.responses = responses or [(200, b'{"imageId":1}')]
        self.requests: list[urllib.request.Request] = []

    def __call__(self, req: urllib.request.Request, timeout: float | None = None) -> Any:
        self.requests.append(req)
        index = min(len(self.requests) - 1, len(self.responses) - 1)
        outcome = self.responses[index]
        if isinstance(outcome, Exception):
            raise outcome
        status, body = outcome
        if status >= 400:
            raise urllib.error.HTTPError(
                req.full_url, status, "error", {}, io.BytesIO(body)  # type: ignore[arg-type]
            )
        return _FakeResponse(status, body)

    @property
    def last_parts(self) -> dict[str, dict[str, Any]]:
        req = self.requests[-1]
        return parse_multipart(req.data, req.get_header("Content-type"))


@pytest.fixture
def recorder(monkeypatch: pytest.MonkeyPatch) -> Recorder:
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)
    return rec


@pytest.fixture
def image_file(tmp_path):
    path = tmp_path / "frame_20260803_101500.png"
    path.write_bytes(PNG_BYTES)
    return path


def make_uploader(**overrides: Any) -> ImageUploader:
    kwargs: dict[str, Any] = {
        "base_url": "http://127.0.0.1:9999",
        "path": "/api/v1/images",
        "device_id": "raspberry-01",
        "enabled": True,
        "max_attempts": 3,
        "retry_backoff_sec": 0.0,
        "sleep_fn": lambda _sec: None,
    }
    kwargs.update(overrides)
    return ImageUploader(**kwargs)


# --------------------------------------------------------------------------
# multipart 조립 자체
# --------------------------------------------------------------------------


def test_encode_multipart_builds_parsable_body():
    parts = [
        MultipartPart(name="metadata", data=b'{"a":1}', content_type="application/json"),
        MultipartPart(name="file", data=PNG_BYTES, filename="frame.png", content_type="image/png"),
    ]
    body, content_type = encode_multipart(parts, boundary="TESTBOUND")

    assert content_type == "multipart/form-data; boundary=TESTBOUND"
    assert body.endswith(b"--TESTBOUND--\r\n")

    parsed = parse_multipart(body, content_type)
    assert parsed["metadata"]["data"] == b'{"a":1}'
    assert parsed["metadata"]["content_type"] == "application/json"
    assert parsed["file"]["filename"] == "frame.png"
    assert parsed["file"]["data"] == PNG_BYTES


def test_encode_multipart_escapes_quotes_in_filename():
    parts = [MultipartPart(name="file", data=b"x", filename='we"ird\r\n.png')]
    body, _ = encode_multipart(parts, boundary="B")
    header_line = body.split(b"\r\n")[1]
    # 헤더 주입이 되지 않아야 한다 — 개행 제거 + 따옴표 이스케이프
    assert b"\n" not in header_line
    assert b'filename="we\\"ird.png"' in header_line


# --------------------------------------------------------------------------
# 티켓 6 + 7 — 파일 + 메타데이터 동시 전송
# --------------------------------------------------------------------------


def test_upload_sends_file_and_metadata_together(recorder: Recorder, image_file):
    uploader = make_uploader()
    result = uploader.upload_file(
        image_file,
        request_id="req-42",
        captured_at="2026-08-03T10:15:00Z",
        width=320,
        height=240,
        driver="mock",
    )

    assert result.ok is True
    assert result.status_code == 200
    assert result.attempts == 1
    assert len(recorder.requests) == 1

    req = recorder.requests[0]
    assert req.full_url == "http://127.0.0.1:9999/api/v1/images"
    assert req.get_method() == "POST"
    assert req.get_header("Content-type").startswith("multipart/form-data; boundary=")
    assert req.get_header("Content-length") == str(len(req.data))

    parts = recorder.last_parts
    # 파일 파트
    assert parts["file"]["filename"] == image_file.name
    assert parts["file"]["data"] == PNG_BYTES
    assert parts["file"]["content_type"] == "image/png"
    # 메타데이터 파트
    meta = json.loads(parts["metadata"]["data"].decode("utf-8"))
    assert meta["requestId"] == "req-42"
    assert meta["deviceId"] == "raspberry-01"
    assert meta["fileName"] == image_file.name
    assert meta["capturedAt"] == "2026-08-03T10:15:00Z"
    assert meta["contentType"] == "image/png"
    assert meta["fileSize"] == len(PNG_BYTES)
    assert meta["width"] == 320 and meta["height"] == 240
    assert meta["driver"] == "mock"


def test_metadata_mode_fields_sends_each_key_as_form_field(recorder: Recorder, image_file):
    uploader = make_uploader(metadata_mode="fields")
    assert uploader.upload_file(image_file, request_id="req-1").ok

    parts = recorder.last_parts
    assert "metadata" not in parts
    assert parts["requestId"]["data"] == b"req-1"
    assert parts["deviceId"]["data"] == b"raspberry-01"
    assert parts["fileName"]["data"] == image_file.name.encode("utf-8")
    assert parts["file"]["filename"] == image_file.name


def test_metadata_mode_both_sends_json_part_and_fields(recorder: Recorder, image_file):
    uploader = make_uploader(metadata_mode="both")
    assert uploader.upload_file(image_file, request_id="req-1").ok

    parts = recorder.last_parts
    assert "metadata" in parts
    assert parts["requestId"]["data"] == b"req-1"


def test_field_map_renames_and_drops_keys(recorder: Recorder, image_file):
    uploader = make_uploader(
        field_map={"fileName": "imageName", "driver": None, "fileSize": None},
        metadata_field="meta",
    )
    assert uploader.upload_file(image_file, request_id="req-1", driver="mock").ok

    meta = json.loads(recorder.last_parts["meta"]["data"].decode("utf-8"))
    assert meta["imageName"] == image_file.name
    assert "fileName" not in meta
    assert "driver" not in meta
    assert "fileSize" not in meta


def test_extra_fields_and_custom_file_field(recorder: Recorder, image_file):
    uploader = make_uploader(file_field="image", extra_fields={"source": "raspberry"})
    assert uploader.upload_file(image_file, request_id="req-1").ok

    parts = recorder.last_parts
    assert parts["image"]["filename"] == image_file.name
    assert parts["source"]["data"] == b"raspberry"


def test_extra_metadata_is_merged(recorder: Recorder, image_file):
    uploader = make_uploader()
    assert uploader.upload_file(image_file, request_id="req-1", extra={"plantId": 7}).ok

    meta = json.loads(recorder.last_parts["metadata"]["data"].decode("utf-8"))
    assert meta["plantId"] == 7


def test_timestamp_mode_epoch_millis(recorder: Recorder, image_file):
    uploader = make_uploader(timestamp_mode="epoch_millis")
    assert uploader.upload_file(image_file, request_id="r", captured_at="2026-08-03T10:15:00Z").ok

    meta = json.loads(recorder.last_parts["metadata"]["data"].decode("utf-8"))
    assert meta["capturedAt"] == 1785752100000  # 2026-08-03T10:15:00Z


def test_captured_at_defaults_to_now_when_missing(recorder: Recorder, image_file):
    uploader = make_uploader()
    assert uploader.upload_file(image_file, request_id="r").ok

    meta = json.loads(recorder.last_parts["metadata"]["data"].decode("utf-8"))
    assert meta["capturedAt"].endswith("Z")


# --------------------------------------------------------------------------
# CaptureResult 연동 (읽기 전용)
# --------------------------------------------------------------------------


def test_upload_capture_uses_capture_result_fields(recorder: Recorder, image_file):
    capture = CaptureResult(
        timestamp="2026-08-03T10:15:00Z",
        path=str(image_file),
        width=1920,
        height=1080,
        driver="picamera2",
        ok=True,
    )
    result = make_uploader().upload_capture(capture, request_id=17)
    assert result.ok is True

    meta = json.loads(recorder.last_parts["metadata"]["data"].decode("utf-8"))
    assert meta["requestId"] == 17
    assert meta["fileName"] == image_file.name
    assert meta["width"] == 1920 and meta["height"] == 1080
    assert meta["driver"] == "picamera2"


def test_upload_capture_skips_failed_capture(recorder: Recorder, caplog):
    capture = CaptureResult(
        timestamp="2026-08-03T10:15:00Z",
        path="data/camera/nope.jpg",
        width=0,
        height=0,
        driver="picamera2",
        ok=False,
        error="camera not connected",
    )
    with caplog.at_level(logging.ERROR, logger=LOGGER_NAME):
        result = make_uploader().upload_capture(capture, request_id="req-1")

    assert result.ok is False
    assert result.code == "CAPTURE_FAILED"
    assert recorder.requests == []
    assert "camera not connected" in caplog.text


# --------------------------------------------------------------------------
# 예외 처리 — 메타데이터 / 파일 / 비활성
# --------------------------------------------------------------------------


def test_missing_request_id_is_rejected_before_any_request(recorder: Recorder, image_file, caplog):
    with caplog.at_level(logging.ERROR, logger=LOGGER_NAME):
        result = make_uploader().upload_file(image_file, request_id=None)

    assert result.ok is False
    assert result.code == "INVALID_METADATA"
    assert "requestId" in result.message
    assert recorder.requests == [], "메타데이터 오류인데 서버로 전송되었습니다"
    assert "메타데이터 오류로 업로드 중단" in caplog.text


def test_wrong_request_id_type_is_rejected(recorder: Recorder, image_file):
    result = make_uploader().upload_file(image_file, request_id={"nope": 1})
    assert result.code == "INVALID_METADATA"
    assert "형식 오류" in result.message
    assert recorder.requests == []


def test_required_field_disabled_in_field_map_is_rejected(recorder: Recorder, image_file):
    uploader = make_uploader(field_map={"deviceId": None})
    result = uploader.upload_file(image_file, request_id="req-1")

    assert result.code == "INVALID_METADATA"
    assert "field_map" in result.message
    assert recorder.requests == []


def test_extra_required_field_can_be_configured(recorder: Recorder, image_file):
    uploader = make_uploader(required_fields=("requestId", "plantId"))
    assert uploader.upload_file(image_file, request_id="r").code == "INVALID_METADATA"
    assert uploader.upload_file(image_file, request_id="r", extra={"plantId": 3}).ok is True


def test_missing_file_is_reported_not_raised(recorder: Recorder, tmp_path, caplog):
    with caplog.at_level(logging.ERROR, logger=LOGGER_NAME):
        result = make_uploader().upload_file(tmp_path / "gone.png", request_id="req-1")

    assert result.ok is False
    assert result.code == "FILE_NOT_FOUND"
    assert recorder.requests == []
    assert "업로드할 파일이 없습니다" in caplog.text


def test_disabled_uploader_never_touches_network(recorder: Recorder, image_file):
    result = make_uploader(enabled=False).upload_file(image_file, request_id="req-1")
    assert result.ok is True
    assert result.code == "DISABLED"
    assert recorder.requests == []


# --------------------------------------------------------------------------
# 재시도 정책
# --------------------------------------------------------------------------


def test_retries_on_server_error_then_succeeds(monkeypatch, image_file, caplog):
    rec = Recorder([(503, b"unavailable"), (200, b'{"imageId":9}')])
    monkeypatch.setattr(urllib.request, "urlopen", rec)
    delays: list[float] = []

    uploader = make_uploader(retry_backoff_sec=1.0, sleep_fn=delays.append)
    with caplog.at_level(logging.DEBUG, logger=LOGGER_NAME):
        result = uploader.upload_file(image_file, request_id="req-1")

    assert result.ok is True
    assert result.attempts == 2
    assert len(rec.requests) == 2
    assert delays == [1.0]
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert warnings and "업로드 재시도 1/3" in warnings[0].message
    infos = [r for r in caplog.records if r.levelno == logging.INFO]
    assert any("업로드 성공" in r.message for r in infos)


def test_retries_on_network_error_then_final_failure(monkeypatch, image_file, caplog):
    error = urllib.error.URLError("connection refused")
    rec = Recorder([error, error, error])
    monkeypatch.setattr(urllib.request, "urlopen", rec)
    delays: list[float] = []

    uploader = make_uploader(retry_backoff_sec=1.0, sleep_fn=delays.append)
    with caplog.at_level(logging.DEBUG, logger=LOGGER_NAME):
        result = uploader.upload_file(image_file, request_id="req-1")

    assert result.ok is False
    assert result.code == "NETWORK_ERROR"
    assert result.attempts == 3
    assert len(rec.requests) == 3
    assert delays == [1.0, 2.0]  # 지수 백오프
    assert sum(1 for r in caplog.records if r.levelno == logging.WARNING) == 2
    errors = [r for r in caplog.records if r.levelno == logging.ERROR]
    assert errors and "업로드 최종 실패" in errors[0].message


def test_backoff_is_capped(monkeypatch, image_file):
    error = urllib.error.URLError("boom")
    monkeypatch.setattr(urllib.request, "urlopen", Recorder([error] * 5))
    delays: list[float] = []

    uploader = make_uploader(
        max_attempts=5,
        retry_backoff_sec=1.0,
        retry_backoff_max_sec=2.0,
        sleep_fn=delays.append,
    )
    uploader.upload_file(image_file, request_id="req-1")
    assert delays == [1.0, 2.0, 2.0, 2.0]


def test_client_error_is_not_retried(monkeypatch, image_file, caplog):
    rec = Recorder([(400, b'{"message":"bad request"}')])
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    with caplog.at_level(logging.DEBUG, logger=LOGGER_NAME):
        result = make_uploader().upload_file(image_file, request_id="req-1")

    assert result.ok is False
    assert result.code == "HTTP_ERROR"
    assert result.status_code == 400
    assert result.attempts == 1
    assert len(rec.requests) == 1
    assert not [r for r in caplog.records if r.levelno == logging.WARNING]
    assert "bad request" in caplog.text


def test_max_attempts_one_disables_retry(monkeypatch, image_file):
    rec = Recorder([(500, b"x"), (200, b"ok")])
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    result = make_uploader(max_attempts=1).upload_file(image_file, request_id="req-1")
    assert result.ok is False
    assert len(rec.requests) == 1


# --------------------------------------------------------------------------
# 로깅 — 응답 기록 + 비밀값 마스킹
# --------------------------------------------------------------------------


def test_success_logs_server_response_body(monkeypatch, image_file, caplog):
    monkeypatch.setattr(urllib.request, "urlopen", Recorder([(201, b'{"imageId":123}')]))

    with caplog.at_level(logging.INFO, logger=LOGGER_NAME):
        result = make_uploader().upload_file(image_file, request_id="req-1")

    assert result.status_code == 201
    assert result.response_body == '{"imageId":123}'
    assert '{"imageId":123}' in caplog.text
    assert "HTTP 201" in caplog.text


def test_response_body_secrets_are_redacted_in_logs(monkeypatch, image_file, caplog):
    body = b'{"accessToken":"super-secret-value","imageId":1}'
    monkeypatch.setattr(urllib.request, "urlopen", Recorder([(200, body)]))

    with caplog.at_level(logging.INFO, logger=LOGGER_NAME):
        result = make_uploader().upload_file(image_file, request_id="req-1")

    assert "super-secret-value" not in caplog.text
    assert "[REDACTED]" in caplog.text
    # 결과 객체에는 원문이 남는다 (호출자가 필요하면 쓸 수 있게). 로그에만 마스킹.
    assert "super-secret-value" in result.response_body


def test_redact_masks_bearer_and_token_fields():
    assert "abc123" not in redact("Authorization: Bearer abc123")
    assert "k-1" not in redact('{"api_key":"k-1"}')


def test_long_response_body_is_truncated_in_logs(monkeypatch, image_file, caplog):
    monkeypatch.setattr(urllib.request, "urlopen", Recorder([(200, b"y" * 5000)]))

    with caplog.at_level(logging.INFO, logger=LOGGER_NAME):
        make_uploader(log_body_chars=50).upload_file(image_file, request_id="req-1")

    assert "y" * 50 in caplog.text
    assert "y" * 51 not in caplog.text


# --------------------------------------------------------------------------
# 인증 헤더
# --------------------------------------------------------------------------


def test_bearer_auth_header_is_attached_and_never_logged(
    recorder: Recorder, image_file, monkeypatch, caplog
):
    monkeypatch.setenv("UPLOAD_API_TOKEN", "tok-abcdef")
    uploader = make_uploader(auth_scheme="bearer")

    with caplog.at_level(logging.DEBUG, logger=LOGGER_NAME):
        assert uploader.upload_file(image_file, request_id="req-1").ok

    assert recorder.requests[0].get_header("Authorization") == "Bearer tok-abcdef"
    assert "tok-abcdef" not in caplog.text


def test_custom_auth_header_scheme(recorder: Recorder, image_file, monkeypatch):
    monkeypatch.setenv("STATION_KEY", "k-1")
    uploader = make_uploader(
        auth_scheme="header", auth_header="X-API-KEY", auth_token_env="STATION_KEY"
    )
    assert uploader.upload_file(image_file, request_id="req-1").ok
    assert recorder.requests[0].get_header("X-api-key") == "k-1"


def test_missing_token_fails_before_sending(recorder: Recorder, image_file, monkeypatch, caplog):
    """인증이 필요한데 토큰이 없으면 보내지 않는다.

    스펙 미확정 시절엔 경고만 하고 전송했지만, 실제 서버(X-Device-Token)는 토큰이 없으면
    401 만 준다. 헛된 요청을 보내고 401 을 해석하게 하는 것보다 로컬에서 끊는 편이 진단이 쉽다.
    """
    monkeypatch.delenv("UPLOAD_API_TOKEN", raising=False)
    uploader = make_uploader(auth_scheme="bearer")

    with caplog.at_level(logging.ERROR, logger=LOGGER_NAME):
        result = uploader.upload_file(image_file, request_id="req-1")

    assert result.ok is False
    assert result.code == "MISSING_TOKEN"
    assert recorder.requests == []
    assert "UPLOAD_API_TOKEN" in caplog.text


def test_no_auth_header_when_scheme_is_none(recorder: Recorder, image_file, monkeypatch):
    monkeypatch.setenv("UPLOAD_API_TOKEN", "tok-abcdef")
    assert make_uploader().upload_file(image_file, request_id="req-1").ok
    assert recorder.requests[0].get_header("Authorization") is None


# --------------------------------------------------------------------------
# config 연동
# --------------------------------------------------------------------------


def test_build_image_uploader_reads_upload_section():
    config = {
        "upload": {
            "enabled": True,
            "base_url": "http://server:8080/",
            "path": "api/v1/plant-images",
            "device_id": "raspberry-01",
            "max_attempts": 5,
            "file_field": "image",
            "metadata_mode": "fields",
            "field_map": {"fileName": "imageName"},
            "auth": {"scheme": "bearer", "token_env": "MY_TOKEN"},
        }
    }
    uploader = build_image_uploader(config)
    assert uploader.enabled is True
    assert uploader.url == "http://server:8080/api/v1/plant-images"
    assert uploader.max_attempts == 5
    assert uploader.file_field == "image"
    assert uploader.metadata_mode == "fields"
    assert uploader.field_map["fileName"] == "imageName"
    # 지정하지 않은 키는 기본 매핑 유지
    assert uploader.field_map["requestId"] == DEFAULT_FIELD_MAP["requestId"]
    assert uploader.auth_token_env == "MY_TOKEN"


def test_build_image_uploader_without_section_is_disabled():
    uploader = build_image_uploader({})
    assert uploader.enabled is False
    assert uploader.max_attempts == 3


def test_base_url_env_override(monkeypatch):
    monkeypatch.setenv("UPLOAD_BASE_URL", "http://override:9000")
    uploader = build_image_uploader({"upload": {"base_url": "http://ignored:1"}})
    assert uploader.url.startswith("http://override:9000")


@pytest.mark.parametrize("config_path", ["config/default.yaml", "config/raspberry_pi.yaml"])
def test_project_yaml_upload_section_matches_photo_api_contract(config_path, monkeypatch):
    """두 yaml 이 potner 사진 API 계약과 어긋나지 않는지 — 어기면 서버가 조용히 거절한다."""
    monkeypatch.delenv("DEVICE_ID", raising=False)
    config = load_config(config_path)
    assert "upload" in config, f"{config_path} 에 upload 섹션이 없습니다"
    uploader = build_image_uploader(config)

    assert uploader.url == "http://i15e104.p.ssafy.io/api/v1/device/photos"
    assert uploader.file_field == "file"          # 다른 이름이면 서버가 400
    assert uploader.auth_scheme == "header"
    assert uploader.auth_header == "X-Device-Token"
    # 바디 메타데이터를 보내면 안 되고, capturedAt 은 쿼리로 나가야 한다
    assert uploader.metadata_mode == "none"
    assert uploader.query_fields == ("capturedAt",)
    # '+09:00' 이 쿼리에서 깨지므로 Z 정규화가 켜져 있어야 한다
    assert uploader.timestamp_mode == "iso8601_z"
    # 409(그날 사진 이미 있음)는 정상 — 실패로 다루면 자동 촬영 체인이 끊긴다
    assert 409 in uploader.success_status
    assert uploader.max_bytes == 10 * 1024 * 1024
    # 4xx 는 재시도 대상이 아니다 (토큰·배정 문제는 사람이 고쳐야 한다)
    assert not [code for code in uploader.retry_on_status if 400 <= code < 500 and code != 408
                and code != 425 and code != 429]


def test_only_raspberry_config_enables_upload(monkeypatch):
    """PC(mock) 설정으로는 실서버에 사진이 새어나가지 않아야 한다."""
    monkeypatch.delenv("DEVICE_ID", raising=False)
    assert build_image_uploader(load_config("config/default.yaml")).enabled is False
    assert build_image_uploader(load_config("config/raspberry_pi.yaml")).enabled is True


# --------------------------------------------------------------------------
# 로컬 http.server 스텁 — 서버가 실제로 수신·파싱하는지 (실네트워크 아님, 127.0.0.1)
# --------------------------------------------------------------------------


class _StubHandler(BaseHTTPRequestHandler):
    received: list[dict[str, Any]] = []

    def do_POST(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler 규약
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        parts = parse_multipart(body, self.headers.get("Content-Type", ""))
        _StubHandler.received.append(
            {"path": self.path, "parts": parts, "auth": self.headers.get("Authorization")}
        )
        payload = json.dumps({"imageId": len(_StubHandler.received), "status": "STORED"})
        raw = payload.encode("utf-8")
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *_args: Any) -> None:
        return None


@pytest.fixture
def stub_server(monkeypatch):
    monkeypatch.setenv("no_proxy", "*")
    monkeypatch.setenv("NO_PROXY", "*")
    _StubHandler.received = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_local_stub_server_receives_image_and_metadata(stub_server, image_file, caplog):
    host, port = stub_server.server_address[:2]
    uploader = make_uploader(base_url=f"http://{host}:{port}", path="/api/v1/images")

    with caplog.at_level(logging.INFO, logger=LOGGER_NAME):
        result = uploader.upload_file(
            image_file,
            request_id="req-e2e",
            captured_at="2026-08-03T10:15:00Z",
            width=320,
            height=240,
            driver="mock",
        )

    assert result.ok is True
    assert result.status_code == 201
    assert json.loads(result.response_body)["status"] == "STORED"

    assert len(_StubHandler.received) == 1
    received = _StubHandler.received[0]
    assert received["path"] == "/api/v1/images"
    # 서버가 받은 파일 바이트가 원본과 동일해야 한다
    assert received["parts"]["file"]["data"] == image_file.read_bytes()
    assert received["parts"]["file"]["filename"] == image_file.name
    meta = json.loads(received["parts"]["metadata"]["data"].decode("utf-8"))
    assert meta["requestId"] == "req-e2e"
    assert meta["deviceId"] == "raspberry-01"
    assert meta["fileName"] == image_file.name
    assert meta["capturedAt"] == "2026-08-03T10:15:00Z"
    assert meta["fileSize"] == len(PNG_BYTES)
    assert "업로드 성공" in caplog.text


def test_local_stub_server_receives_mock_camera_capture(stub_server, tmp_path):
    from src.vision.mock import MockCamera
    from src.vision.store import CameraStore

    store = CameraStore(tmp_path / "camera")
    capture = store.capture(MockCamera(width=320, height=240))
    assert capture.ok

    host, port = stub_server.server_address[:2]
    uploader = make_uploader(base_url=f"http://{host}:{port}")
    result = uploader.upload_capture(capture, request_id=101)

    assert result.ok is True
    received = _StubHandler.received[0]
    assert received["parts"]["file"]["data"] == open(capture.path, "rb").read()
    meta = json.loads(received["parts"]["metadata"]["data"].decode("utf-8"))
    assert meta["requestId"] == 101
    assert meta["driver"] == "mock"
    assert meta["capturedAt"] == capture.timestamp


def test_local_stub_server_retry_after_first_failure(stub_server, image_file, caplog, monkeypatch):
    host, port = stub_server.server_address[:2]
    delays: list[float] = []
    uploader = make_uploader(
        base_url=f"http://{host}:{port}",
        path="/api/v1/images",
        retry_backoff_sec=0.0,
        sleep_fn=delays.append,
    )

    calls = {"n": 0}
    real_urlopen = urllib.request.urlopen

    def flaky(req, timeout=None):
        # 첫 시도만 연결 실패로 만들고, 재시도는 실제 스텁 서버로 흘려보낸다
        calls["n"] += 1
        if calls["n"] == 1:
            raise urllib.error.URLError("simulated connection reset")
        return real_urlopen(req, timeout=timeout)

    monkeypatch.setattr(urllib.request, "urlopen", flaky)
    with caplog.at_level(logging.DEBUG, logger=LOGGER_NAME):
        result = uploader.upload_file(image_file, request_id="req-retry")

    assert result.ok is True
    assert result.attempts == 2
    assert len(_StubHandler.received) == 1
    assert any(r.levelno == logging.WARNING for r in caplog.records)
