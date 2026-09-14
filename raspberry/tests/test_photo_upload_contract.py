"""potner 사진 API 계약 테스트.

  POST http://i15e104.p.ssafy.io/api/v1/device/photos
  파일 파트 `file` · 헤더 `X-Device-Token` · 쿼리 `capturedAt`(Z 형식) · 201 성공 · 409 도 정상

계약을 어기면 서버가 400/401 로 조용히 거절하거나(사진 유실) 409 를 실패로 처리해
자동 촬영 체인이 끊긴다. 실네트워크 금지 — urlopen 을 monkeypatch 한다.
"""

from __future__ import annotations

import io
import urllib.error
import urllib.parse
import urllib.request
from email.parser import BytesParser
from email.policy import default as email_policy
from typing import Any, Optional

import pytest

from src.config import load_config
from src.transport.uploader import build_image_uploader, shrink_jpeg
from src.vision.base import CaptureResult

JPEG_BYTES = b"\xff\xd8\xff\xe0" + b"fake-jpeg-body" * 8


class _FakeResponse:
    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *_exc: Any) -> None:
        return None


class Recorder:
    """urlopen 대체 — 요청을 붙잡고 정해둔 응답을 순서대로 돌려준다."""

    def __init__(self, responses: Optional[list[Any]] = None) -> None:
        self.responses = responses or [(201, b'{"photoId":1}')]
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
    def last(self) -> urllib.request.Request:
        return self.requests[-1]

    @property
    def last_query(self) -> dict[str, list[str]]:
        return urllib.parse.parse_qs(urllib.parse.urlsplit(self.last.full_url).query)

    @property
    def last_parts(self) -> dict[str, dict[str, Any]]:
        """multipart 바디를 실제 파싱해 {파트이름: {filename, payload}} 로 돌려준다."""
        content_type = self.last.get_header("Content-type")
        raw = b"Content-Type: " + content_type.encode() + b"\r\nMIME-Version: 1.0\r\n\r\n"
        message = BytesParser(policy=email_policy).parsebytes(raw + self.last.data)
        out: dict[str, dict[str, Any]] = {}
        for part in message.iter_parts():  # type: ignore[attr-defined]
            name = part.get_param("name", header="content-disposition")
            out[str(name)] = {
                "filename": part.get_filename(),
                "payload": part.get_payload(decode=True),
            }
        return out


@pytest.fixture
def uploader(monkeypatch):
    """실제 raspberry_pi.yaml 로 만든 업로더 — 설정과 코드를 함께 검증한다."""
    monkeypatch.delenv("DEVICE_ID", raising=False)
    monkeypatch.setenv("POTNER_UPLOAD_TOKEN", "tok-abc123")
    return build_image_uploader(load_config("config/raspberry_pi.yaml"))


def write_jpeg(tmp_path, name: str = "frame_20260803_111500.jpg") -> str:
    path = tmp_path / name
    path.write_bytes(JPEG_BYTES)
    return str(path)


def capture(path: str, timestamp: str) -> CaptureResult:
    return CaptureResult(
        timestamp=timestamp, path=path, width=1920, height=1080, driver="picamera2", ok=True
    )


# --- 요청 형태 --------------------------------------------------------------


def test_file_part_is_named_file_and_carries_the_image(uploader, tmp_path, monkeypatch):
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    result = uploader.upload_file(write_jpeg(tmp_path), request_id="r-1")

    assert result.ok is True
    parts = rec.last_parts
    # 파트 이름이 'file' 이 아니면 서버가 400 을 준다
    assert set(parts) == {"file"}
    assert parts["file"]["payload"] == JPEG_BYTES
    assert parts["file"]["filename"] == "frame_20260803_111500.jpg"


def test_device_token_header_is_sent_and_url_matches_contract(uploader, tmp_path, monkeypatch):
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    uploader.upload_file(write_jpeg(tmp_path), request_id="r-2")

    assert rec.last.get_header("X-device-token") == "tok-abc123"
    assert rec.last.full_url.startswith("http://i15e104.p.ssafy.io/api/v1/device/photos")


def test_plant_id_is_never_sent(uploader, tmp_path, monkeypatch):
    """서버가 토큰 -> 로봇 -> 활성 배정으로 스스로 정한다. 보내면 계약 위반."""
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    uploader.upload_file(write_jpeg(tmp_path), request_id="r-3")

    assert "plantId" not in rec.last_parts
    assert "plantId" not in rec.last_query


# --- capturedAt (핵심 함정) --------------------------------------------------


def test_captured_at_goes_to_query_as_z_format(uploader, tmp_path, monkeypatch):
    """'+09:00' 은 쿼리에서 '+' 가 공백으로 깨진다 — UTC Z 로 정규화해야 한다."""
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    # CaptureResult.timestamp 가 실제로 만드는 형식 (로컬 오프셋)
    uploader.upload_capture(
        capture(write_jpeg(tmp_path), "2026-08-03T11:15:00+09:00"), request_id="r-4"
    )

    assert rec.last_query["capturedAt"] == ["2026-08-03T02:15:00Z"]
    assert "+" not in urllib.parse.urlsplit(rec.last.full_url).query
    # 바디가 아니라 쿼리로 나가야 한다
    assert "capturedAt" not in rec.last_parts


def test_captured_at_already_in_z_stays_unchanged(uploader, tmp_path, monkeypatch):
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    uploader.upload_capture(
        capture(write_jpeg(tmp_path), "2026-08-03T02:15:00Z"), request_id="r-5"
    )

    assert rec.last_query["capturedAt"] == ["2026-08-03T02:15:00Z"]


# --- 응답 처리 --------------------------------------------------------------


def test_201_is_success(uploader, tmp_path, monkeypatch):
    monkeypatch.setattr(urllib.request, "urlopen", Recorder([(201, b'{"photoId":7}')]))
    result = uploader.upload_file(write_jpeg(tmp_path), request_id="r-6")
    assert result.ok is True
    assert result.status_code == 201


def test_409_already_exists_today_is_not_a_failure(uploader, tmp_path, monkeypatch, caplog):
    """서버는 타임랩스 간격 유지를 위해 하루 한 장만 저장한다. 409 는 정상 동작이다."""
    rec = Recorder([(409, b'{"code":"PHOTO_ALREADY_EXISTS_FOR_DATE"}')])
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    with caplog.at_level("INFO", logger="src.transport.uploader"):
        result = uploader.upload_file(write_jpeg(tmp_path), request_id="r-7")

    assert result.ok is True
    assert result.status_code == 409
    # 재시도하지 않는다 (1회로 끝)
    assert len(rec.requests) == 1
    assert not [r for r in caplog.records if r.levelname == "ERROR"]


@pytest.mark.parametrize(
    "status, body",
    [
        (401, b'{"code":"INVALID_DEVICE_TOKEN"}'),
        (404, b'{"code":"PLANT_ASSIGNMENT_NOT_FOUND"}'),
        (400, b'{"code":"INVALID_REQUEST"}'),
        (413, b"payload too large"),
    ],
)
def test_4xx_fails_without_retry(uploader, tmp_path, monkeypatch, status, body):
    """토큰·배정 문제는 사람이 고쳐야 한다 — 재시도해도 같은 결과다."""
    rec = Recorder([(status, body)])
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    result = uploader.upload_file(write_jpeg(tmp_path), request_id="r-8")

    assert result.ok is False
    assert result.status_code == status
    assert len(rec.requests) == 1


def test_network_error_is_retried(uploader, tmp_path, monkeypatch):
    rec = Recorder([urllib.error.URLError("conn refused"), (201, b"{}")])
    monkeypatch.setattr(urllib.request, "urlopen", rec)
    uploader._sleep = lambda _s: None

    result = uploader.upload_file(write_jpeg(tmp_path), request_id="r-9")

    assert result.ok is True
    assert len(rec.requests) == 2


def test_500_is_retried(uploader, tmp_path, monkeypatch):
    rec = Recorder([(503, b"unavailable"), (201, b"{}")])
    monkeypatch.setattr(urllib.request, "urlopen", rec)
    uploader._sleep = lambda _s: None

    result = uploader.upload_file(write_jpeg(tmp_path), request_id="r-10")

    assert result.ok is True
    assert len(rec.requests) == 2


# --- 크기 제한 --------------------------------------------------------------


def test_oversize_image_is_reencoded_when_pillow_is_available(uploader, tmp_path, monkeypatch):
    pytest.importorskip("PIL")
    from PIL import Image

    big = tmp_path / "big.jpg"
    # 노이즈가 많아야 JPEG 가 잘 안 줄어든다 — 재인코딩 경로를 실제로 태우기 위함
    Image.new("RGB", (400, 300), (120, 200, 90)).save(big, format="JPEG", quality=95)
    uploader.max_bytes = 1500  # 실제 10 MiB 대신 작게 잡아 경로만 검증

    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)
    result = uploader.upload_file(str(big), request_id="r-11")

    assert result.ok is True
    assert len(rec.last_parts["file"]["payload"]) <= 1500


def test_oversize_fails_clearly_when_it_cannot_shrink(uploader, tmp_path, monkeypatch):
    monkeypatch.setattr("src.transport.uploader.shrink_jpeg", lambda _d, _m: None)
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)
    uploader.max_bytes = 10

    result = uploader.upload_file(write_jpeg(tmp_path), request_id="r-12")

    assert result.ok is False
    assert result.code == "FILE_TOO_LARGE"
    assert rec.requests == []  # 네트워크를 타지 않는다


def test_shrink_jpeg_returns_none_for_undecodable_bytes():
    assert shrink_jpeg(b"not-an-image", 100) is None


# --- 토큰 --------------------------------------------------------------------


def test_missing_token_fails_locally_without_calling_the_server(tmp_path, monkeypatch):
    """토큰 없이 보내면 401 만 받는다 — 네트워크를 타기 전에 명확히 끊는다."""
    monkeypatch.delenv("DEVICE_ID", raising=False)
    # load_config 가 .env 를 읽어 POTNER_UPLOAD_TOKEN 을 채운다. 먼저 지우면 여기서
    # 되살아나므로 반드시 config 를 읽은 "다음"에 지워야 한다 (개발 PC .env 에 실제
    # 토큰이 들어 있으면 이 순서를 어긴 테스트는 조용히 통과해버린다).
    config = load_config("config/raspberry_pi.yaml")
    monkeypatch.delenv("POTNER_UPLOAD_TOKEN", raising=False)
    up = build_image_uploader(config)
    rec = Recorder()
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    result = up.upload_file(write_jpeg(tmp_path), request_id="r-13")

    assert result.ok is False
    assert result.code == "MISSING_TOKEN"
    assert rec.requests == []
    assert "장치 관리" in result.message


def test_token_value_never_appears_in_logs(uploader, tmp_path, monkeypatch, caplog):
    rec = Recorder([(201, b'{"accessToken":"tok-abc123"}')])
    monkeypatch.setattr(urllib.request, "urlopen", rec)

    with caplog.at_level("DEBUG", logger="src.transport.uploader"):
        uploader.upload_file(write_jpeg(tmp_path), request_id="r-14")

    assert "tok-abc123" not in caplog.text
