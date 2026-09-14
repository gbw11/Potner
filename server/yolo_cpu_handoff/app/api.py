"""FastAPI adapter for lifecycle-managed CPU-only inference."""

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
import logging
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .admission import (
    PredictAdmissionMiddleware,
    is_request_body_too_large,
)
from .config import PredictionOptions, Settings
from .errors import (
    HandoffError,
    InvalidRequestError,
    ModelNotReadyError,
    PayloadTooLargeError,
)
from .images import decode_image_bytes
from .inference import YoloPredictor
from .schemas import ErrorBody, ErrorResponse, PredictionResponse


_UPLOAD_CHUNK_BYTES = 64 * 1024
_LOGGER_NAME = "yolo_cpu_handoff.api"
_SAFE_ERROR_MESSAGES = {
    "INVALID_IMAGE": "Unable to decode image.",
    "PAYLOAD_TOO_LARGE": "Image exceeds the upload limit.",
    "MODEL_NOT_READY": "The model is not ready.",
    "INFERENCE_FAILED": "Inference failed.",
}


def create_app(
    settings: Settings | None = None,
    predictor: YoloPredictor | None = None,
) -> FastAPI:
    """Build an app whose predictor and readiness state are instance-local."""
    resolved_settings = settings or Settings.from_env()
    resolved_predictor = predictor or YoloPredictor(resolved_settings)
    logger = logging.getLogger(_LOGGER_NAME)
    logger.setLevel(resolved_settings.log_level)
    load_error: ModelNotReadyError | None = None

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        nonlocal load_error
        try:
            await asyncio.to_thread(resolved_predictor.load)
            load_error = None
        except ModelNotReadyError as error:
            load_error = error
            _log_diagnostic(
                logger,
                event="model_load_failed",
                error_code="MODEL_NOT_READY",
                error=error,
            )
        except Exception as error:
            load_error = ModelNotReadyError("The model is not ready.")
            _log_diagnostic(
                logger,
                event="model_load_failed",
                error_code="MODEL_NOT_READY",
                error=error,
            )
        yield

    app = FastAPI(lifespan=lifespan)
    app.add_middleware(
        PredictAdmissionMiddleware,
        max_upload_bytes=resolved_settings.max_upload_bytes,
        max_concurrency=resolved_settings.max_concurrency,
    )

    @app.exception_handler(HandoffError)
    async def handoff_error_handler(
        request: Request,
        error: HandoffError,
    ) -> JSONResponse:
        if error.code == "INFERENCE_FAILED":
            _log_diagnostic(
                logger,
                event="prediction_failed",
                error_code=error.code,
                error=error,
                request_id=request.state.request_id,
            )
        return _error_response(
            request,
            code=error.code,
            message=_safe_error_message(error),
            status_code=error.http_status,
        )

    @app.exception_handler(RequestValidationError)
    async def request_validation_error_handler(
        request: Request,
        _: RequestValidationError,
    ) -> JSONResponse:
        return _error_response(
            request,
            code="INVALID_REQUEST",
            message="Invalid request.",
            status_code=400,
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(
        request: Request,
        error: StarletteHTTPException,
    ) -> JSONResponse:
        if is_request_body_too_large(error):
            return _error_response(
                request,
                code="PAYLOAD_TOO_LARGE",
                message=_SAFE_ERROR_MESSAGES["PAYLOAD_TOO_LARGE"],
                status_code=413,
            )
        if error.status_code == 404:
            return _error_response(
                request,
                code="INVALID_REQUEST",
                message="Resource not found.",
                status_code=404,
            )
        return _error_response(
            request,
            code="INVALID_REQUEST",
            message="Invalid request.",
            status_code=400,
        )

    @app.exception_handler(Exception)
    async def unexpected_error_handler(
        request: Request,
        error: Exception,
    ) -> JSONResponse:
        _log_diagnostic(
            logger,
            event="unexpected_failure",
            error_code="INFERENCE_FAILED",
            error=error,
            request_id=request.state.request_id,
        )
        return _error_response(
            request,
            code="INFERENCE_FAILED",
            message="Inference failed.",
            status_code=500,
        )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "up"}

    @app.get("/ready")
    async def ready() -> dict[str, object]:
        if load_error is not None:
            raise load_error

        try:
            info = dict(resolved_predictor.ready_info())
        except Exception as error:
            raise ModelNotReadyError("The model is not ready.") from error
        if info.get("ready") is not True:
            raise ModelNotReadyError("The model is not ready.")

        info["weights"] = Path(str(info.get("weights", ""))).name
        info["device"] = "cpu"
        return info

    @app.post("/predict", response_model=PredictionResponse)
    async def predict(
        request: Request,
        file: UploadFile = File(...),
        conf: str | None = Form(default=None),
        imgsz: str | None = Form(default=None),
    ) -> PredictionResponse:
        if load_error is not None:
            raise load_error

        options = _prediction_options(resolved_settings, conf, imgsz)
        payload = await _read_bounded(file, resolved_settings.max_upload_bytes)
        decoded = await asyncio.to_thread(
            decode_image_bytes,
            payload,
            resolved_settings.max_upload_bytes,
        )
        source_name = _safe_source_name(file.filename)

        return await asyncio.to_thread(
            resolved_predictor.predict,
            decoded,
            source_name,
            options,
            request.state.request_id,
        )

    return app


async def _read_bounded(upload: UploadFile, max_bytes: int) -> bytes:
    payload = bytearray()
    try:
        while True:
            remaining = max_bytes - len(payload)
            chunk = await upload.read(min(_UPLOAD_CHUNK_BYTES, remaining + 1))
            if not chunk:
                return bytes(payload)
            payload.extend(chunk)
            if len(payload) > max_bytes:
                raise PayloadTooLargeError()
    finally:
        await upload.close()


def _prediction_options(
    settings: Settings,
    conf: str | None,
    imgsz: str | None,
) -> PredictionOptions:
    try:
        confidence = settings.confidence if conf is None else float(conf)
    except ValueError as error:
        raise InvalidRequestError("conf must be a number") from error

    try:
        image_size = settings.image_size if imgsz is None else int(imgsz)
    except ValueError as error:
        raise InvalidRequestError("imgsz must be an integer") from error

    try:
        return PredictionOptions(
            confidence=confidence,
            image_size=image_size,
        )
    except ValueError as error:
        raise InvalidRequestError(str(error)) from error


def _safe_source_name(filename: str | None) -> str:
    normalized = (filename or "").replace("\\", "/")
    return Path(normalized).name or "upload"


def _safe_error_message(error: HandoffError) -> str:
    return _SAFE_ERROR_MESSAGES.get(error.code, error.message)


def _log_diagnostic(
    logger: logging.Logger,
    *,
    event: str,
    error_code: str,
    error: Exception,
    request_id: str | None = None,
) -> None:
    logger.error(
        "YOLO service failure.",
        extra={
            "event": event,
            "request_id": request_id,
            "error_code": error_code,
        },
        exc_info=(type(error), error, error.__traceback__),
    )


def _error_response(
    request: Request,
    *,
    code: str,
    message: str,
    status_code: int,
) -> JSONResponse:
    request_id = getattr(request.state, "request_id", str(uuid4()))
    body = ErrorResponse(
        error=ErrorBody(
            code=code,
            message=message,
            request_id=request_id,
        )
    )
    return JSONResponse(
        status_code=status_code,
        content=body.model_dump(mode="json"),
        headers={"X-Request-ID": request_id},
    )


app = create_app()
