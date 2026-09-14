import asyncio
import json


def _predict_scope(headers=()):
    return {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/predict",
        "raw_path": b"/predict",
        "query_string": b"",
        "headers": list(headers),
        "client": ("127.0.0.1", 12345),
        "server": ("testserver", 80),
        "state": {},
    }


def _response_parts(messages):
    start = next(message for message in messages if message["type"] == "http.response.start")
    body = b"".join(
        message.get("body", b"")
        for message in messages
        if message["type"] == "http.response.body"
    )
    return start, body


def test_chunked_predict_body_stops_before_handler_after_raw_cap():
    from app.admission import PredictAdmissionMiddleware

    async def scenario():
        handler_reached = False

        async def inner(scope, receive, send):
            nonlocal handler_reached
            while True:
                message = await receive()
                if not message.get("more_body", False):
                    break
            handler_reached = True
            await send({"type": "http.response.start", "status": 204, "headers": []})
            await send({"type": "http.response.body", "body": b""})

        middleware = PredictAdmissionMiddleware(
            inner,
            max_upload_bytes=16,
            max_concurrency=1,
        )
        chunks = iter(
            [
                {
                    "type": "http.request",
                    "body": b"x" * (512 * 1024),
                    "more_body": True,
                },
                {
                    "type": "http.request",
                    "body": b"x" * (512 * 1024),
                    "more_body": True,
                },
                {
                    "type": "http.request",
                    "body": b"x" * 17,
                    "more_body": False,
                },
            ]
        )
        sent = []

        async def receive():
            return next(chunks)

        async def send(message):
            sent.append(message)

        await middleware(_predict_scope(), receive, send)
        return handler_reached, sent

    handler_reached, sent = asyncio.run(scenario())
    start, raw_body = _response_parts(sent)
    body = json.loads(raw_body)
    headers = dict(start["headers"])

    assert handler_reached is False
    assert start["status"] == 413
    assert body["error"]["code"] == "PAYLOAD_TOO_LARGE"
    assert body["error"]["message"] == "Image exceeds the upload limit."
    assert body["error"]["request_id"]
    assert headers[b"x-request-id"].decode() == body["error"]["request_id"]


def test_known_oversize_predict_body_is_rejected_without_entering_inner_app():
    from app.admission import PredictAdmissionMiddleware

    async def scenario():
        inner_reached = False

        async def inner(scope, receive, send):
            nonlocal inner_reached
            inner_reached = True

        middleware = PredictAdmissionMiddleware(
            inner,
            max_upload_bytes=16,
            max_concurrency=1,
        )
        sent = []

        async def receive():
            raise AssertionError("oversize Content-Length must not be read")

        async def send(message):
            sent.append(message)

        headers = [(b"content-length", str(16 + 1024 * 1024 + 1).encode())]
        await middleware(_predict_scope(headers), receive, send)
        return inner_reached, sent

    inner_reached, sent = asyncio.run(scenario())
    start, raw_body = _response_parts(sent)

    assert inner_reached is False
    assert start["status"] == 413
    assert json.loads(raw_body)["error"]["code"] == "PAYLOAD_TOO_LARGE"


def test_predict_admission_bounds_whole_request_before_body_parsing():
    from app.admission import PredictAdmissionMiddleware

    async def scenario():
        first_entered = asyncio.Event()
        release_first = asyncio.Event()
        active = 0
        max_active = 0
        entered = []

        async def inner(scope, receive, send):
            nonlocal active, max_active
            request_number = scope["request_number"]
            active += 1
            max_active = max(max_active, active)
            entered.append(request_number)
            if request_number == 1:
                first_entered.set()
                await release_first.wait()
            await receive()
            await send({"type": "http.response.start", "status": 204, "headers": []})
            await send({"type": "http.response.body", "body": b""})
            active -= 1

        middleware = PredictAdmissionMiddleware(
            inner,
            max_upload_bytes=16,
            max_concurrency=1,
        )

        async def invoke(number):
            scope = _predict_scope()
            scope["request_number"] = number

            async def receive():
                return {
                    "type": "http.request",
                    "body": b"",
                    "more_body": False,
                }

            async def send(_):
                pass

            await middleware(scope, receive, send)

        first = asyncio.create_task(invoke(1))
        await first_entered.wait()
        second = asyncio.create_task(invoke(2))
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        entered_while_first_held = list(entered)
        release_first.set()
        await asyncio.gather(first, second)
        return entered_while_first_held, entered, max_active

    entered_while_first_held, entered, max_active = asyncio.run(scenario())

    assert entered_while_first_held == [1]
    assert entered == [1, 2]
    assert max_active == 1
