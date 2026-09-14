"""Spring 서버로 센서 JSON POST + 파일 multipart/form-data 업로드.

`requests` 의존성 없이 표준 라이브러리(urllib)만 쓴다 — Pi 는 오프라인일 수 있고
의존성 추가는 배포 비용이 있다. 그래서 multipart 바디는 여기서 직접 조립한다.
"""

from __future__ import annotations

import json
import mimetypes
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any, Iterable, Optional

CRLF = b"\r\n"


def post_json(url: str, payload: dict[str, Any], *, timeout: float = 10.0) -> tuple[int, str]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            return int(resp.status), body
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        return int(exc.code), body


@dataclass(frozen=True)
class MultipartPart:
    """multipart/form-data 파트 1개.

    - 일반 텍스트 필드: filename=None, content_type=None
    - JSON 파트 (Spring 의 `@RequestPart` 대상): filename=None, content_type="application/json"
    - 파일 파트: filename 지정 + content_type 지정
    """

    name: str
    data: bytes
    filename: Optional[str] = None
    content_type: Optional[str] = None


def guess_content_type(file_name: str, default: str = "application/octet-stream") -> str:
    guessed, _ = mimetypes.guess_type(file_name)
    return guessed or default


def _escape_header_value(value: str) -> str:
    # Content-Disposition 헤더가 깨지거나 주입되지 않도록 따옴표·개행 제거 (RFC 7578 권고)
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\r", "").replace("\n", "")


def encode_multipart(
    parts: Iterable[MultipartPart],
    *,
    boundary: Optional[str] = None,
) -> tuple[bytes, str]:
    """파트 목록 → (바디 바이트, Content-Type 헤더값).

    boundary 를 넘기지 않으면 매 호출 새로 생성한다 (테스트에서만 고정값을 넘긴다).
    """
    bound = boundary or f"----potner{uuid.uuid4().hex}"
    marker = f"--{bound}".encode("utf-8")

    chunks: list[bytes] = []
    for part in parts:
        disposition = f'form-data; name="{_escape_header_value(part.name)}"'
        if part.filename is not None:
            disposition = f'{disposition}; filename="{_escape_header_value(part.filename)}"'
        chunks.append(marker + CRLF)
        chunks.append(f"Content-Disposition: {disposition}".encode("utf-8") + CRLF)
        if part.content_type:
            chunks.append(f"Content-Type: {part.content_type}".encode("utf-8") + CRLF)
        chunks.append(CRLF)
        chunks.append(part.data)
        chunks.append(CRLF)
    chunks.append(marker + b"--" + CRLF)

    body = b"".join(chunks)
    return body, f"multipart/form-data; boundary={bound}"


def post_multipart(
    url: str,
    parts: Iterable[MultipartPart],
    *,
    headers: Optional[dict[str, str]] = None,
    timeout: float = 10.0,
    method: str = "POST",
    boundary: Optional[str] = None,
) -> tuple[int, str]:
    """multipart/form-data 전송. (status, body) 반환.

    HTTPError(4xx/5xx)는 상태코드로 환원하고, 네트워크 계열 오류(URLError/OSError)는
    그대로 올린다 — 호출자가 재시도 여부를 판단해야 하기 때문 (post_json 과 동일 규약).
    """
    body, content_type = encode_multipart(parts, boundary=boundary)
    req_headers = {
        "Content-Type": content_type,
        "Content-Length": str(len(body)),
        "Accept": "application/json",
    }
    if headers:
        req_headers.update(headers)

    req = urllib.request.Request(url, data=body, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            return int(getattr(resp, "status", 200)), text
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        return int(exc.code), text
