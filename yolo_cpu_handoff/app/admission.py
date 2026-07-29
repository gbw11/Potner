"""Outer ASGI admission for bounded predict request bodies and concurrency."""

import asyncio
from uuid import uuid4

from starlette.datastructures import Headers, MutableHeaders
from starlette.formparsers import MultiPartException
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from .schemas import ErrorBody, ErrorResponse


MULTIPART_OVERHEAD_BYTES = 1024 * 1024
_REQUEST_BODY_TOO_LARGE_DETAIL = "request body exceeds raw predict limit"


class _RequestBodyTooLarge(MultiPartException):
    """Internal control signal raised before multipart parsing can finish."""

    def __init__(self) -> None:
        super().__init__(_REQUEST_BODY_TOO_LARGE_DETAIL)


class PredictAdmissionMiddleware:
    """Bound raw predict ingress and hold one slot for its entire lifecycle."""

    def __init__(
        self,
        app: ASGIApp,
        *,
        max_upload_bytes: int,
        max_concurrency: int,
    ) -> None:
        self._app = app
        self._max_request_bytes = max_upload_bytes + MULTIPART_OVERHEAD_BYTES
        self._slots = asyncio.Semaphore(max_concurrency)

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        state = scope.setdefault("state", {})
        request_id = state.setdefault("request_id", str(uuid4()))

        async def send_with_request_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                message = dict(message)
                raw_headers = list(message.get("headers", []))
                headers = MutableHeaders(raw=raw_headers)
                headers["X-Request-ID"] = request_id
                message["headers"] = raw_headers
            await send(message)

        is_predict = (
            scope.get("method") == "POST" and scope.get("path") == "/predict"
        )
        if not is_predict:
            await self._app(scope, receive, send_with_request_id)
            return

        content_length = _content_length(scope)
        if (
            content_length is not None
            and content_length > self._max_request_bytes
        ):
            await _send_payload_too_large(
                scope,
                receive,
                send_with_request_id,
                request_id,
            )
            return

        async with self._slots:
            received_bytes = 0

            async def receive_bounded() -> Message:
                nonlocal received_bytes
                message = await receive()
                if message["type"] == "http.request":
                    received_bytes += len(message.get("body", b""))
                    if received_bytes > self._max_request_bytes:
                        raise _RequestBodyTooLarge()
                return message

            try:
                await self._app(scope, receive_bounded, send_with_request_id)
            except _RequestBodyTooLarge:
                await _send_payload_too_large(
                    scope,
                    receive,
                    send_with_request_id,
                    request_id,
                )


def is_request_body_too_large(error: Exception) -> bool:
    """Recognize overflow after framework body parsing wraps the signal."""
    if (
        getattr(error, "detail", None)
        == _REQUEST_BODY_TOO_LARGE_DETAIL
    ):
        return True

    cause = error.__cause__
    while cause is not None:
        if isinstance(cause, _RequestBodyTooLarge):
            return True
        cause = cause.__cause__
    return False


def _content_length(scope: Scope) -> int | None:
    value = Headers(scope=scope).get("content-length")
    if value is None:
        return None
    try:
        parsed = int(value)
    except ValueError:
        return None
    return parsed if parsed >= 0 else None


async def _send_payload_too_large(
    scope: Scope,
    receive: Receive,
    send: Send,
    request_id: str,
) -> None:
    body = ErrorResponse(
        error=ErrorBody(
            code="PAYLOAD_TOO_LARGE",
            message="Image exceeds the upload limit.",
            request_id=request_id,
        )
    )
    response = JSONResponse(
        status_code=413,
        content=body.model_dump(mode="json"),
    )
    await response(scope, receive, send)
