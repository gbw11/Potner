"""촬영 이미지 + 촬영 메타데이터를 백엔드로 multipart/form-data 업로드.

========================================================================
서버 API 스펙 미확정 (2026-08). 엔드포인트·필드명·인증 방식은 **전부**
config 의 `upload:` 섹션에서 읽으며 코드에 하드코딩하지 않는다.
스펙이 확정되면 바꿀 곳은 아래 5개뿐이고, 전부 config 키다:

  1. upload.base_url / upload.path        → 엔드포인트
  2. upload.file_field                    → 파일 파트 이름
  3. upload.metadata_mode / metadata_field→ 메타데이터를 JSON 파트로 보낼지 폼 필드로 보낼지
  4. upload.field_map                     → 우리 표준 키 → 서버 필드명 (null 이면 미전송)
  5. upload.auth.*                        → 인증 헤더 이름/스킴/토큰 환경변수

코드 쪽 기본값은 `DEFAULT_FIELD_MAP` / `_DEFAULTS` 한 곳에만 모여 있다.
그 밖의 곳에 서버 필드명을 새로 적지 말 것.
========================================================================

전송 구조:
  - 파일 파트 1개 + 메타데이터(JSON 파트 또는 개별 폼 필드)
  - 실패해도 예외를 밖으로 던지지 않는다 (수집/촬영 루프가 죽으면 안 됨).
    결과는 항상 `UploadResult` 로 표현한다.
  - 재시도는 **HTTP 네트워크/서버 오류에 대한 재시도**다. 촬영 자체의 재시도와는 무관.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import urlencode

from ..device import resolve_device_id
from ..integrations.spring import MultipartPart, guess_content_type, post_multipart

log = logging.getLogger(__name__)

# 우리 표준(canonical) 메타데이터 키 → 서버 필드명.
# 서버 스펙 확정 시 config `upload.field_map` 으로 덮어쓴다. 값 null = 그 항목 미전송.
DEFAULT_FIELD_MAP: dict[str, Optional[str]] = {
    "requestId": "requestId",
    "deviceId": "deviceId",
    "fileName": "fileName",
    "capturedAt": "capturedAt",
    "contentType": "contentType",
    "fileSize": "fileSize",
    "width": "width",
    "height": "height",
    "driver": "driver",
}

DEFAULT_REQUIRED_FIELDS = ("requestId", "deviceId", "fileName", "capturedAt")

# 일시적 오류로 보고 재시도할 상태코드
DEFAULT_RETRY_STATUS = (408, 425, 429, 500, 502, 503, 504)

# 응답 본문 로그에서 가릴 값들 (토큰이 에코되어 돌아오는 서버가 있다)
_SECRET_PATTERNS = (
    re.compile(r"(?i)bearer\s+[A-Za-z0-9\-._~+/=]+"),
    re.compile(
        r'(?i)"(authorization|token|access_?token|refresh_?token|api_?key|secret|password)"'
        r'\s*:\s*"[^"]*"'
    ),
)


@dataclass(frozen=True)
class UploadResult:
    """업로드 1건의 결과. 실패도 예외가 아니라 이 객체로 표현한다."""

    ok: bool
    status_code: Optional[int]
    message: str
    attempts: int = 0
    code: Optional[str] = None
    metadata: dict[str, Any] = field(default_factory=dict)
    response_body: str = ""


def _utc_now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def shrink_jpeg(data: bytes, max_bytes: int) -> Optional[bytes]:
    """`max_bytes` 안에 들어가도록 JPEG 품질을 낮춰 재인코딩. 실패하면 None.

    Pillow 가 없으면(=PC mock 환경) None 을 돌려주고 호출부가 명확히 실패시킨다.
    Pi 에는 python3-pil 이 있어 실제로 동작한다.
    """
    try:
        import io

        from PIL import Image
    except ImportError:
        log.error("이미지가 상한을 넘었지만 Pillow 가 없어 재인코딩할 수 없습니다 (apt python3-pil)")
        return None

    try:
        with Image.open(io.BytesIO(data)) as image:
            frame = image.convert("RGB")
            for quality in (85, 75, 65, 55, 45):
                buffer = io.BytesIO()
                frame.save(buffer, format="JPEG", quality=quality, optimize=True)
                shrunk = buffer.getvalue()
                if len(shrunk) <= max_bytes:
                    log.warning(
                        f"이미지가 상한을 넘어 품질 {quality} 로 재인코딩했습니다 "
                        f"({len(data)} → {len(shrunk)} bytes)"
                    )
                    return shrunk
    except Exception as exc:  # noqa: BLE001 — 재인코딩 실패로 촬영 흐름을 깨뜨리지 않는다
        log.error(f"이미지 재인코딩 실패: {type(exc).__name__}: {exc}")
        return None
    return None


def redact(text: str) -> str:
    """응답 본문 로그용 마스킹 — 토큰/인증 값이 로그에 남지 않게 한다."""
    masked = text
    for pattern in _SECRET_PATTERNS:
        masked = pattern.sub("[REDACTED]", masked)
    return masked


class ImageUploader:
    """이미지 파일 + 메타데이터 multipart 업로드 (재시도 포함).

    `enabled: false` 면 네트워크를 타지 않고 ok=True/code="DISABLED" 로 즉시 반환한다
    (SpringSoilPublisher 와 같은 게이트 규약).
    """

    def __init__(
        self,
        base_url: str,
        path: str = "/api/v1/images",
        *,
        method: str = "POST",
        device_id: str = "unknown-device",
        timeout_sec: float = 10.0,
        enabled: bool = False,
        max_attempts: int = 3,
        retry_backoff_sec: float = 1.0,
        retry_backoff_max_sec: float = 8.0,
        retry_on_status: tuple[int, ...] = DEFAULT_RETRY_STATUS,
        file_field: str = "file",
        metadata_mode: str = "json_part",
        metadata_field: str = "metadata",
        metadata_content_type: str = "application/json",
        field_map: Optional[dict[str, Optional[str]]] = None,
        required_fields: tuple[str, ...] = DEFAULT_REQUIRED_FIELDS,
        extra_fields: Optional[dict[str, Any]] = None,
        timestamp_mode: str = "iso8601",
        query_fields: tuple[str, ...] = (),
        success_status: tuple[int, ...] = (),
        max_bytes: int = 0,
        auth_scheme: str = "none",
        auth_header: str = "Authorization",
        auth_token_env: str = "UPLOAD_API_TOKEN",
        log_body_chars: int = 300,
        sleep_fn: Optional[Callable[[float], None]] = None,
    ) -> None:
        self.enabled = enabled
        self.base_url = base_url.rstrip("/")
        self.path = path if path.startswith("/") else f"/{path}"
        self.method = method.upper()
        self.device_id = device_id
        self.timeout_sec = timeout_sec
        self.max_attempts = max(1, int(max_attempts))
        self.retry_backoff_sec = max(0.0, float(retry_backoff_sec))
        self.retry_backoff_max_sec = max(0.0, float(retry_backoff_max_sec))
        self.retry_on_status = tuple(retry_on_status)
        self.file_field = file_field
        self.metadata_mode = metadata_mode.lower()
        self.metadata_field = metadata_field
        self.metadata_content_type = metadata_content_type
        self.field_map = {**DEFAULT_FIELD_MAP, **(field_map or {})}
        self.required_fields = tuple(required_fields)
        self.extra_fields = dict(extra_fields or {})
        self.timestamp_mode = timestamp_mode.lower()
        self.query_fields = tuple(query_fields)
        self.success_status = tuple(success_status)
        self.max_bytes = max(0, int(max_bytes))
        self.auth_scheme = auth_scheme.lower()
        self.auth_header = auth_header
        self.auth_token_env = auth_token_env
        self.log_body_chars = int(log_body_chars)
        self._sleep = sleep_fn or time.sleep

    # --- URL / 인증 ---

    @property
    def url(self) -> str:
        return f"{self.base_url}{self.path}"

    def missing_token(self) -> bool:
        """인증이 필요한데 토큰이 없는 상태인가."""
        if self.auth_scheme in {"", "none"}:
            return False
        return not (os.getenv(self.auth_token_env) or "").strip()

    def _auth_headers(self) -> dict[str, str]:
        if self.auth_scheme in {"", "none"}:
            return {}
        token = (os.getenv(self.auth_token_env) or "").strip()
        if not token:
            # 토큰 값은 절대 로그에 남기지 않는다. 환경변수 '이름'만 남긴다.
            log.warning(f"업로드 인증 토큰이 비어 있습니다 (env {self.auth_token_env})")
            return {}
        if self.auth_scheme == "bearer":
            return {self.auth_header: f"Bearer {token}"}
        # 'header' — X-Device-Token / X-API-KEY 같은 원시 헤더
        return {self.auth_header: token}

    # --- 메타데이터 ---

    def _format_timestamp(self, value: Optional[str]) -> Any:
        raw = value or _utc_now_z()
        if self.timestamp_mode == "iso8601":
            return raw
        try:
            text = raw[:-1] + "+00:00" if raw.endswith("Z") else raw
            parsed = datetime.fromisoformat(text)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
        except ValueError:
            log.error(f"capturedAt 파싱 실패 — 원문 그대로 전송합니다: {raw}")
            return raw
        if self.timestamp_mode == "epoch_millis":
            return int(parsed.timestamp() * 1000)
        if self.timestamp_mode == "iso8601_z":
            # 쿼리 스트링에서 '+09:00' 의 '+' 가 공백으로 해석되므로 UTC Z 로 정규화한다.
            # CaptureResult.timestamp 는 로컬 오프셋 형식이라 이 변환이 반드시 필요하다.
            return parsed.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        return raw

    def build_metadata(
        self,
        *,
        request_id: Any,
        file_name: str,
        captured_at: Optional[str] = None,
        content_type: Optional[str] = None,
        file_size: Optional[int] = None,
        width: Optional[int] = None,
        height: Optional[int] = None,
        driver: Optional[str] = None,
        extra: Optional[dict[str, Any]] = None,
    ) -> dict[str, Any]:
        """표준 키로 된 메타데이터 생성 (서버 필드명 매핑 전)."""
        meta: dict[str, Any] = {
            "requestId": request_id,
            "deviceId": self.device_id,
            "fileName": file_name,
            "capturedAt": self._format_timestamp(captured_at),
            "contentType": content_type,
            "fileSize": file_size,
            "width": width,
            "height": height,
            "driver": driver,
        }
        if extra:
            meta.update(extra)
        return meta

    def validate_metadata(self, meta: dict[str, Any]) -> Optional[str]:
        """필수 항목 누락/형식 오류를 문자열로 반환. 문제 없으면 None."""
        missing = [
            key
            for key in self.required_fields
            if meta.get(key) is None or (isinstance(meta[key], str) and not meta[key].strip())
        ]
        if missing:
            return f"필수 메타데이터 누락: {', '.join(missing)}"

        disabled = [
            key
            for key in self.required_fields
            if key in self.field_map and not self.field_map.get(key)
        ]
        if disabled:
            return f"필수 메타데이터가 field_map 에서 비활성화됨: {', '.join(disabled)}"

        request_id = meta.get("requestId")
        if not isinstance(request_id, (str, int)):
            return f"requestId 형식 오류: {type(request_id).__name__}"
        return None

    def map_metadata(self, meta: dict[str, Any]) -> dict[str, Any]:
        """표준 키 → 서버 필드명. 매핑이 null 인 키와 값이 None 인 항목은 제외."""
        mapped: dict[str, Any] = {}
        for key, value in meta.items():
            if value is None:
                continue
            target = self.field_map.get(key, key)
            if not target:
                continue
            mapped[str(target)] = value
        return mapped

    def partition_mapped(self, mapped: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        """매핑된 메타데이터를 (쿼리 스트링용, 바디 파트용) 으로 가른다.

        `query_fields` 에 적힌 표준 키는 multipart 바디가 아니라 URL 쿼리로 나간다 —
        potner 사진 API 처럼 파일 파트 하나만 받고 나머지는 쿼리로 받는 서버용.
        """
        targets = set()
        for key in self.query_fields:
            target = self.field_map.get(key, key)
            if target:
                targets.add(str(target))
        query = {k: v for k, v in mapped.items() if k in targets}
        body = {k: v for k, v in mapped.items() if k not in targets}
        return query, body

    # --- 업로드 ---

    def upload_capture(self, capture: Any, *, request_id: Any, extra: Optional[dict[str, Any]] = None) -> UploadResult:
        """`CaptureResult` 를 그대로 받아 업로드 (읽기 전용으로만 사용)."""
        if not getattr(capture, "ok", False):
            message = f"촬영 실패분은 업로드하지 않습니다: {getattr(capture, 'error', None)}"
            log.error(f"업로드 생략 (requestId={request_id}): {message}")
            return UploadResult(
                ok=False, status_code=None, message=message, code="CAPTURE_FAILED"
            )
        return self.upload_file(
            capture.path,
            request_id=request_id,
            captured_at=getattr(capture, "timestamp", None),
            width=getattr(capture, "width", None),
            height=getattr(capture, "height", None),
            driver=getattr(capture, "driver", None),
            extra=extra,
        )

    def upload_file(
        self,
        file_path: str | Path,
        *,
        request_id: Any,
        captured_at: Optional[str] = None,
        width: Optional[int] = None,
        height: Optional[int] = None,
        driver: Optional[str] = None,
        extra: Optional[dict[str, Any]] = None,
    ) -> UploadResult:
        if not self.enabled:
            log.debug(f"업로드 비활성 (upload.enabled=false) — 생략: {file_path}")
            return UploadResult(ok=True, status_code=None, message="disabled", code="DISABLED")

        if self.missing_token():
            # 토큰 없이 보내봐야 서버가 401 을 줄 뿐이다. 네트워크를 타기 전에 끊는다.
            message = (
                f"업로드 토큰이 없습니다 (env {self.auth_token_env}). "
                "앱의 장치 관리 → 토큰 재발급으로 받아 .env 에 넣으세요."
            )
            log.error(message)
            return UploadResult(
                ok=False, status_code=None, message=message, code="MISSING_TOKEN"
            )

        path = Path(file_path)
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            message = f"업로드할 파일이 없습니다: {path}"
            log.error(message)
            return UploadResult(ok=False, status_code=None, message=message, code="FILE_NOT_FOUND")
        except OSError as exc:
            message = f"업로드할 파일을 읽을 수 없습니다 ({path}): {exc}"
            log.error(message)
            return UploadResult(ok=False, status_code=None, message=message, code="FILE_READ_ERROR")

        if self.max_bytes and len(data) > self.max_bytes:
            shrunk = shrink_jpeg(data, self.max_bytes)
            if shrunk is None:
                message = (
                    f"이미지가 업로드 상한을 넘습니다: {len(data)} bytes "
                    f"(상한 {self.max_bytes}). 재인코딩도 실패했습니다."
                )
                log.error(message)
                return UploadResult(
                    ok=False, status_code=None, message=message, code="FILE_TOO_LARGE"
                )
            data = shrunk

        content_type = guess_content_type(path.name)
        meta = self.build_metadata(
            request_id=request_id,
            file_name=path.name,
            captured_at=captured_at,
            content_type=content_type,
            file_size=len(data),
            width=width,
            height=height,
            driver=driver,
            extra=extra,
        )
        problem = self.validate_metadata(meta)
        if problem is not None:
            log.error(f"메타데이터 오류로 업로드 중단 ({path.name}): {problem}")
            return UploadResult(
                ok=False,
                status_code=None,
                message=problem,
                code="INVALID_METADATA",
                metadata=meta,
            )

        mapped = self.map_metadata(meta)
        query, body_meta = self.partition_mapped(mapped)
        parts = self._build_parts(
            body_meta, file_name=path.name, data=data, content_type=content_type
        )
        return self._send_with_retry(parts, meta=meta, file_name=path.name, query=query)

    def _build_parts(
        self,
        mapped: dict[str, Any],
        *,
        file_name: str,
        data: bytes,
        content_type: str,
    ) -> list[MultipartPart]:
        parts: list[MultipartPart] = []

        if self.metadata_mode in {"json_part", "both"}:
            body = json.dumps(mapped, ensure_ascii=False).encode("utf-8")
            parts.append(
                MultipartPart(
                    name=self.metadata_field,
                    data=body,
                    content_type=self.metadata_content_type,
                )
            )
        if self.metadata_mode in {"fields", "both"}:
            for key, value in mapped.items():
                parts.append(MultipartPart(name=key, data=str(value).encode("utf-8")))

        for key, value in self.extra_fields.items():
            parts.append(MultipartPart(name=str(key), data=str(value).encode("utf-8")))

        parts.append(
            MultipartPart(
                name=self.file_field,
                data=data,
                filename=file_name,
                content_type=content_type,
            )
        )
        return parts

    def _backoff_sec(self, attempt: int) -> float:
        delay = self.retry_backoff_sec * (2 ** (attempt - 1))
        if self.retry_backoff_max_sec > 0:
            delay = min(delay, self.retry_backoff_max_sec)
        return delay

    def _snippet(self, body: str) -> str:
        text = redact(body).strip().replace("\n", " ")
        return text[: self.log_body_chars]

    def _send_with_retry(
        self,
        parts: list[MultipartPart],
        *,
        meta: dict[str, Any],
        file_name: str,
        query: Optional[dict[str, Any]] = None,
    ) -> UploadResult:
        headers = self._auth_headers()
        request_id = meta.get("requestId")
        url = self.url
        if query:
            url = f"{url}?{urlencode({k: str(v) for k, v in query.items()})}"
        last: UploadResult = UploadResult(
            ok=False, status_code=None, message="not attempted", code="NETWORK_ERROR", metadata=meta
        )

        for attempt in range(1, self.max_attempts + 1):
            try:
                status, body = post_multipart(
                    url,
                    parts,
                    headers=headers,
                    timeout=self.timeout_sec,
                    method=self.method,
                )
            except Exception as exc:  # noqa: BLE001 — 업로드 실패로 호출 루프가 죽으면 안 된다
                message = f"업로드 통신 실패: {type(exc).__name__}: {exc}"
                last = UploadResult(
                    ok=False,
                    status_code=None,
                    message=message,
                    attempts=attempt,
                    code="NETWORK_ERROR",
                    metadata=meta,
                )
                if attempt < self.max_attempts:
                    delay = self._backoff_sec(attempt)
                    log.warning(
                        f"업로드 재시도 {attempt}/{self.max_attempts} "
                        f"({file_name}, requestId={request_id}): {message} — {delay}s 후 재시도"
                    )
                    self._sleep(delay)
                    continue
                log.error(
                    f"업로드 최종 실패 ({file_name}, requestId={request_id}, "
                    f"시도 {attempt}회): {message}"
                )
                return last

            snippet = self._snippet(body)
            if 200 <= status < 300:
                log.info(
                    f"업로드 성공 ({file_name}, requestId={request_id}, "
                    f"HTTP {status}, 시도 {attempt}회) 응답: {snippet}"
                )
                return UploadResult(
                    ok=True,
                    status_code=status,
                    message="ok",
                    attempts=attempt,
                    metadata=meta,
                    response_body=body,
                )

            if status in self.success_status:
                # 예: 409 PHOTO_ALREADY_EXISTS_FOR_DATE — 서버가 하루 한 장만 저장하므로
                # 같은 날 두 번째 촬영은 거절된다. 정상 동작이니 실패로 다루지 않는다.
                log.info(
                    f"업로드 완료로 간주 ({file_name}, requestId={request_id}, "
                    f"HTTP {status}, 시도 {attempt}회) 응답: {snippet}"
                )
                return UploadResult(
                    ok=True,
                    status_code=status,
                    message=f"HTTP {status} (성공으로 간주)",
                    attempts=attempt,
                    metadata=meta,
                    response_body=body,
                )

            message = f"HTTP {status}: {snippet}"
            last = UploadResult(
                ok=False,
                status_code=status,
                message=message,
                attempts=attempt,
                code="HTTP_ERROR",
                metadata=meta,
                response_body=body,
            )
            if status in self.retry_on_status and attempt < self.max_attempts:
                delay = self._backoff_sec(attempt)
                log.warning(
                    f"업로드 재시도 {attempt}/{self.max_attempts} "
                    f"({file_name}, requestId={request_id}): {message} — {delay}s 후 재시도"
                )
                self._sleep(delay)
                continue
            log.error(
                f"업로드 최종 실패 ({file_name}, requestId={request_id}, "
                f"시도 {attempt}회): {message}"
            )
            return last

        return last


_DEFAULTS: dict[str, Any] = {
    "enabled": False,
    "base_url": "http://127.0.0.1:8080",
    "path": "/api/v1/images",
    "method": "POST",
    "timeout_sec": 10,
    "max_attempts": 3,
    "retry_backoff_sec": 1.0,
    "retry_backoff_max_sec": 8.0,
    "retry_on_status": list(DEFAULT_RETRY_STATUS),
    "device_id": "auto",
    "file_field": "file",
    "metadata_mode": "json_part",
    "metadata_field": "metadata",
    "metadata_content_type": "application/json",
    "timestamp_mode": "iso8601",
    "query_fields": [],
    "success_status": [],
    "max_bytes": 0,
    "required_fields": list(DEFAULT_REQUIRED_FIELDS),
    "log_body_chars": 300,
}


def build_image_uploader(config: dict[str, Any]) -> ImageUploader:
    """config `upload:` 섹션 → ImageUploader. 섹션이 없어도 비활성 인스턴스를 돌려준다."""
    cfg = {**_DEFAULTS, **((config or {}).get("upload") or {})}
    auth_cfg = cfg.get("auth") or {}

    base_url = str(os.getenv("UPLOAD_BASE_URL") or cfg["base_url"])
    field_map = {**DEFAULT_FIELD_MAP, **(cfg.get("field_map") or {})}

    return ImageUploader(
        base_url=base_url,
        path=str(cfg["path"]),
        method=str(cfg["method"]),
        device_id=resolve_device_id(cfg.get("device_id")),
        timeout_sec=float(cfg["timeout_sec"]),
        enabled=bool(cfg["enabled"]),
        max_attempts=int(cfg["max_attempts"]),
        retry_backoff_sec=float(cfg["retry_backoff_sec"]),
        retry_backoff_max_sec=float(cfg["retry_backoff_max_sec"]),
        retry_on_status=tuple(int(code) for code in cfg["retry_on_status"]),
        file_field=str(cfg["file_field"]),
        metadata_mode=str(cfg["metadata_mode"]),
        metadata_field=str(cfg["metadata_field"]),
        metadata_content_type=str(cfg["metadata_content_type"]),
        field_map=field_map,
        required_fields=tuple(str(key) for key in cfg["required_fields"]),
        extra_fields=cfg.get("extra_fields") or {},
        timestamp_mode=str(cfg["timestamp_mode"]),
        query_fields=tuple(str(key) for key in cfg["query_fields"]),
        success_status=tuple(int(code) for code in cfg["success_status"]),
        max_bytes=int(cfg["max_bytes"]),
        auth_scheme=str(auth_cfg.get("scheme", "none")),
        auth_header=str(auth_cfg.get("header", "Authorization")),
        auth_token_env=str(auth_cfg.get("token_env", "UPLOAD_API_TOKEN")),
        log_body_chars=int(cfg["log_body_chars"]),
    )
