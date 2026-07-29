import asyncio
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import logging
from pathlib import Path
from tempfile import SpooledTemporaryFile
from threading import Barrier, Lock
import time

from PIL import Image
import pytest

from app.config import PredictionOptions, Settings
from app.errors import (
    InferenceFailedError,
    ModelNotReadyError,
    PayloadTooLargeError,
)
from app.schemas import ImageInfo, ModelInfo, PredictionResponse


@pytest.fixture
def settings(tmp_path):
    return Settings(
        weights_path=str(tmp_path / "best.pt"),
        confidence=0.10,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )


@pytest.fixture
def jpeg_bytes():
    buffer = BytesIO()
    Image.new("RGB", (8, 6), color="green").save(buffer, format="JPEG")
    return buffer.getvalue()


class RecordingPredictor:
    def __init__(self, *, load_error=None, predict_error=None, delay=0.0):
        self.load_error = load_error
        self.predict_error = predict_error
        self.delay = delay
        self.load_calls = 0
        self.prediction_calls = []
        self.active_predictions = 0
        self.max_active_predictions = 0
        self._lock = Lock()

    def load(self):
        self.load_calls += 1
        if self.load_error is not None:
            raise self.load_error

    def ready_info(self):
        return {
            "ready": self.load_error is None,
            "weights": "best.pt",
            "device": "cpu",
            "classes": {
                0: "germination",
                1: "vegetative",
                2: "flowering",
            },
        }

    def predict(self, decoded, source_name, options, request_id=None):
        with self._lock:
            self.active_predictions += 1
            self.max_active_predictions = max(
                self.max_active_predictions,
                self.active_predictions,
            )
        try:
            time.sleep(self.delay)
            if self.predict_error is not None:
                raise self.predict_error
            self.prediction_calls.append((decoded, source_name, options, request_id))
            return PredictionResponse(
                request_id=request_id,
                source_name=source_name,
                image=ImageInfo(width=decoded.width, height=decoded.height),
                model=ModelInfo(
                    weights="best.pt",
                    device="cpu",
                    imgsz=options.image_size,
                    confidence_threshold=options.confidence,
                ),
                detections=[],
                detection_count=0,
                inference_ms=1.0,
            )
        finally:
            with self._lock:
                self.active_predictions -= 1


class RecordingUpload:
    def __init__(self, payload):
        self.payload = payload
        self.offset = 0
        self.read_sizes = []
        self.closed = False

    async def read(self, size):
        self.read_sizes.append(size)
        chunk = self.payload[self.offset : self.offset + size]
        self.offset += len(chunk)
        return chunk

    async def close(self):
        self.closed = True


def make_client(settings, predictor, **kwargs):
    from fastapi.testclient import TestClient

    from app.api import create_app

    return TestClient(
        create_app(settings=settings, predictor=predictor),
        **kwargs,
    )


def test_api_module_exports_default_fastapi_application():
    from fastapi import FastAPI

    from app.api import app

    assert isinstance(app, FastAPI)


def test_health_does_not_require_ready_model(settings):
    predictor = RecordingPredictor(
        load_error=ModelNotReadyError(
            f"Could not load {Path(settings.weights_path).resolve()}"
        )
    )

    with make_client(settings, predictor) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "up"}


def test_ready_returns_cpu_model_metadata(settings):
    predictor = RecordingPredictor()

    with make_client(settings, predictor) as client:
        response = client.get("/ready")

    assert response.status_code == 200
    assert response.json() == {
        "ready": True,
        "weights": "best.pt",
        "device": "cpu",
        "classes": {
            "0": "germination",
            "1": "vegetative",
            "2": "flowering",
        },
    }


def test_ready_load_failure_has_stable_sanitized_error(settings):
    internal_path = str(Path(settings.weights_path).resolve())
    predictor = RecordingPredictor(
        load_error=ModelNotReadyError(f"Could not load {internal_path}")
    )

    with make_client(settings, predictor) as client:
        response = client.get("/ready")

    body = response.json()
    assert response.status_code == 503
    assert body["error"]["code"] == "MODEL_NOT_READY"
    assert body["error"]["message"] == "The model is not ready."
    assert body["error"]["request_id"]
    assert internal_path not in response.text
    assert "traceback" not in response.text.lower()


def test_configured_log_level_is_applied_to_application_logger(settings):
    from app.api import create_app

    debug_settings = Settings(
        weights_path=settings.weights_path,
        confidence=settings.confidence,
        image_size=settings.image_size,
        max_upload_bytes=settings.max_upload_bytes,
        max_concurrency=settings.max_concurrency,
        log_level="DEBUG",
    )

    create_app(settings=debug_settings, predictor=RecordingPredictor())

    assert logging.getLogger("yolo_cpu_handoff.api").level == logging.DEBUG


def test_startup_model_failure_logs_structured_diagnostic_cause(
    settings,
    caplog,
):
    internal_path = str(Path(settings.weights_path).resolve())

    class FailingLoadPredictor(RecordingPredictor):
        def load(self):
            try:
                raise RuntimeError(f"checkpoint decoder failed at {internal_path}")
            except RuntimeError as error:
                raise ModelNotReadyError("Unable to load the configured model.") from error

    with caplog.at_level(logging.ERROR, logger="yolo_cpu_handoff.api"):
        with make_client(settings, FailingLoadPredictor()) as client:
            response = client.get("/ready")

    record = next(
        record for record in caplog.records if record.event == "model_load_failed"
    )
    assert record.error_code == "MODEL_NOT_READY"
    assert record.request_id is None
    assert record.exc_info is not None
    assert "checkpoint decoder failed" in caplog.text
    assert response.status_code == 503
    assert internal_path not in response.text
    assert "checkpoint decoder failed" not in response.text


def test_predict_returns_shared_schema(settings, jpeg_bytes):
    predictor = RecordingPredictor()

    with make_client(settings, predictor) as client:
        response = client.post(
            "/predict",
            files={"file": ("plant.jpg", jpeg_bytes, "image/jpeg")},
        )

    assert response.status_code == 200
    assert response.json() == {
        "request_id": response.json()["request_id"],
        "source_name": "plant.jpg",
        "image": {"width": 8, "height": 6},
        "model": {
            "weights": "best.pt",
            "device": "cpu",
            "imgsz": 640,
            "confidence_threshold": 0.1,
        },
        "detections": [],
        "detection_count": 0,
        "inference_ms": 1.0,
    }
    assert response.json()["request_id"]


def test_predict_parses_optional_form_overrides(settings, jpeg_bytes):
    predictor = RecordingPredictor()

    with make_client(settings, predictor) as client:
        response = client.post(
            "/predict",
            files={"file": ("plant.jpg", jpeg_bytes, "image/jpeg")},
            data={"conf": "0.75", "imgsz": "320"},
        )

    assert response.status_code == 200
    assert predictor.prediction_calls[0][2] == PredictionOptions(
        confidence=0.75,
        image_size=320,
    )


@pytest.mark.parametrize(
    ("filename", "content", "content_type"),
    [
        ("bad.jpg", b"bad", "image/jpeg"),
        ("plant.gif", b"GIF89a", "image/gif"),
    ],
)
def test_invalid_image_has_stable_error(
    settings,
    filename,
    content,
    content_type,
):
    predictor = RecordingPredictor()

    with make_client(settings, predictor) as client:
        response = client.post(
            "/predict",
            files={"file": (filename, content, content_type)},
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_IMAGE"
    assert response.json()["error"]["request_id"]


def test_predict_rejects_oversize_upload(settings):
    small_limit = Settings(
        weights_path=settings.weights_path,
        confidence=settings.confidence,
        image_size=settings.image_size,
        max_upload_bytes=16,
        max_concurrency=settings.max_concurrency,
        log_level=settings.log_level,
    )
    predictor = RecordingPredictor()

    with make_client(small_limit, predictor) as client:
        response = client.post(
            "/predict",
            files={"file": ("large.png", b"x" * 17, "image/png")},
        )

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"
    assert not predictor.prediction_calls


def test_chunked_raw_cap_closes_multipart_spool_and_skips_handler(
    settings,
    monkeypatch,
):
    from app.api import create_app
    from starlette import formparsers

    small_limit = Settings(
        weights_path=settings.weights_path,
        confidence=settings.confidence,
        image_size=settings.image_size,
        max_upload_bytes=16,
        max_concurrency=1,
        log_level=settings.log_level,
    )
    predictor = RecordingPredictor()
    spooled_files = []

    def tracking_spooled_file(*args, **kwargs):
        temporary_file = SpooledTemporaryFile(*args, **kwargs)
        spooled_files.append(temporary_file)
        return temporary_file

    monkeypatch.setattr(
        formparsers,
        "SpooledTemporaryFile",
        tracking_spooled_file,
    )
    app = create_app(settings=small_limit, predictor=predictor)
    boundary = b"yolo-test-boundary"
    body = (
        b"--"
        + boundary
        + b'\r\nContent-Disposition: form-data; name="file"; '
        + b'filename="large.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'
        + b"x" * (1024 * 1024)
        + b"\r\n--"
        + boundary
        + b"--\r\n"
    )

    async def invoke():
        chunks = iter(
            body[index : index + 64 * 1024]
            for index in range(0, len(body), 64 * 1024)
        )
        sent = []

        async def receive():
            try:
                chunk = next(chunks)
            except StopIteration:
                return {
                    "type": "http.request",
                    "body": b"",
                    "more_body": False,
                }
            return {
                "type": "http.request",
                "body": chunk,
                "more_body": True,
            }

        async def send(message):
            sent.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0", "spec_version": "2.3"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "http",
            "path": "/predict",
            "raw_path": b"/predict",
            "query_string": b"",
            "headers": [
                (
                    b"content-type",
                    b"multipart/form-data; boundary=" + boundary,
                )
            ],
            "client": ("127.0.0.1", 12345),
            "server": ("testserver", 80),
            "state": {},
        }
        await app(scope, receive, send)
        return sent

    sent = asyncio.run(invoke())
    start = next(
        message for message in sent if message["type"] == "http.response.start"
    )
    raw_response = b"".join(
        message.get("body", b"")
        for message in sent
        if message["type"] == "http.response.body"
    )

    assert start["status"] == 413, raw_response
    assert b"PAYLOAD_TOO_LARGE" in raw_response
    assert spooled_files
    assert all(temporary_file.closed for temporary_file in spooled_files)
    assert not predictor.prediction_calls


def test_bounded_upload_read_stops_after_limit_plus_one_byte():
    from app.api import _read_bounded

    upload = RecordingUpload(b"x" * 1024)

    with pytest.raises(PayloadTooLargeError):
        asyncio.run(_read_bounded(upload, max_bytes=16))

    assert upload.offset == 17
    assert upload.read_sizes == [17]
    assert upload.closed is True


@pytest.mark.parametrize(
    ("form_data", "expected_message"),
    [
        ({"conf": "not-a-number"}, "conf must be a number"),
        ({"conf": "1.1"}, "confidence must be between 0.0 and 1.0"),
        ({"imgsz": "31"}, "image_size must be between 32 and 4096"),
    ],
)
def test_predict_rejects_invalid_options(
    settings,
    jpeg_bytes,
    form_data,
    expected_message,
):
    predictor = RecordingPredictor()

    with make_client(settings, predictor) as client:
        response = client.post(
            "/predict",
            files={"file": ("plant.jpg", jpeg_bytes, "image/jpeg")},
            data=form_data,
        )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert response.json()["error"]["message"] == expected_message
    assert response.json()["error"]["request_id"]


def test_predict_requires_file_with_stable_error(settings):
    predictor = RecordingPredictor()

    with make_client(settings, predictor) as client:
        response = client.post("/predict")

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert response.json()["error"]["request_id"]


def test_predict_model_failure_does_not_leak_internal_detail(
    settings,
    jpeg_bytes,
):
    internal_path = str(Path(settings.weights_path).resolve())
    predictor = RecordingPredictor(
        predict_error=InferenceFailedError(f"failure in {internal_path}")
    )

    with make_client(settings, predictor) as client:
        response = client.post(
            "/predict",
            files={"file": ("plant.jpg", jpeg_bytes, "image/jpeg")},
        )

    assert response.status_code == 500
    assert response.json()["error"]["code"] == "INFERENCE_FAILED"
    assert response.json()["error"]["message"] == "Inference failed."
    assert response.json()["error"]["request_id"]
    assert internal_path not in response.text
    assert "traceback" not in response.text.lower()


def test_inference_failure_logs_request_scoped_diagnostic_without_filename(
    settings,
    jpeg_bytes,
    caplog,
):
    class FailingPredictor(RecordingPredictor):
        def predict(self, decoded, source_name, options, request_id=None):
            try:
                raise RuntimeError("tensor operator exploded")
            except RuntimeError as error:
                raise InferenceFailedError() from error

    with caplog.at_level(logging.ERROR, logger="yolo_cpu_handoff.api"):
        with make_client(settings, FailingPredictor()) as client:
            response = client.post(
                "/predict",
                files={
                    "file": (
                        "patient-secret.jpg",
                        jpeg_bytes,
                        "image/jpeg",
                    )
                },
            )

    record = next(
        record for record in caplog.records if record.event == "prediction_failed"
    )
    assert record.error_code == "INFERENCE_FAILED"
    assert record.request_id == response.json()["error"]["request_id"]
    assert record.exc_info is not None
    assert "tensor operator exploded" in caplog.text
    assert "patient-secret.jpg" not in caplog.text
    assert response.status_code == 500
    assert "tensor operator exploded" not in response.text


def test_unexpected_failure_logs_request_scoped_stack_trace(
    settings,
    jpeg_bytes,
    caplog,
):
    predictor = RecordingPredictor(predict_error=RuntimeError("unexpected kernel fault"))

    with caplog.at_level(logging.ERROR, logger="yolo_cpu_handoff.api"):
        with make_client(
            settings,
            predictor,
            raise_server_exceptions=False,
        ) as client:
            response = client.post(
                "/predict",
                files={"file": ("plant.jpg", jpeg_bytes, "image/jpeg")},
            )

    record = next(
        record for record in caplog.records if record.event == "unexpected_failure"
    )
    assert record.error_code == "INFERENCE_FAILED"
    assert record.request_id == response.json()["error"]["request_id"]
    assert record.exc_info is not None
    assert "unexpected kernel fault" in caplog.text
    assert response.status_code == 500
    assert "unexpected kernel fault" not in response.text
    assert (
        response.headers["X-Request-ID"]
        == response.json()["error"]["request_id"]
    )


def test_lifespan_loads_supplied_predictor_exactly_once(settings):
    predictor = RecordingPredictor()

    with make_client(settings, predictor) as client:
        assert client.get("/ready").status_code == 200
        assert client.get("/health").status_code == 200
        assert client.get("/ready").status_code == 200

    assert predictor.load_calls == 1


def test_predict_runs_off_event_loop_and_respects_concurrency_limit(
    settings,
    jpeg_bytes,
):
    predictor = RecordingPredictor(delay=0.1)
    barrier = Barrier(3)

    with make_client(settings, predictor) as client:
        def send_prediction():
            barrier.wait()
            return client.post(
                "/predict",
                files={"file": ("plant.jpg", jpeg_bytes, "image/jpeg")},
            )

        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(send_prediction)
            second = executor.submit(send_prediction)
            barrier.wait()
            responses = [first.result(), second.result()]

    assert [response.status_code for response in responses] == [200, 200]
    assert predictor.max_active_predictions == 1
