"""Shared lifecycle and response conversion for CPU-only YOLO inference."""

from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
import time
from typing import Any
from uuid import uuid4

from ultralytics import YOLO

from .config import PredictionOptions, Settings
from .errors import InferenceFailedError, ModelNotReadyError
from .images import DecodedImage
from .schemas import (
    BoundingBox,
    Detection,
    ImageInfo,
    ModelInfo,
    PredictionResponse,
)


_EXPECTED_CLASSES = {
    0: "germination",
    1: "vegetative",
    2: "flowering",
}


class YoloPredictor:
    """Own one validated YOLO model and expose CPU-only prediction."""

    def __init__(
        self,
        settings: Settings,
        model_factory: Callable[[str], Any] = YOLO,
    ):
        self._settings = settings
        self._model_factory = model_factory
        self._model: Any | None = None
        self._classes: dict[int, str] | None = None

    def load(self) -> None:
        """Load and validate the configured model once."""
        if self._model is not None:
            return

        weights_path = Path(self._settings.weights_path)
        try:
            is_local_file = weights_path.is_file()
        except OSError as error:
            raise ModelNotReadyError(
                "Unable to load the configured model."
            ) from error
        if not is_local_file:
            raise ModelNotReadyError("Unable to load the configured model.")

        try:
            candidate = self._model_factory(self._settings.weights_path)
            classes = _normalize_names(candidate.names)
            if classes != _EXPECTED_CLASSES:
                raise ValueError("unexpected model classes")
        except Exception as error:
            raise ModelNotReadyError("Unable to load the configured model.") from error

        self._model = candidate
        self._classes = classes

    def ready_info(self) -> dict[str, object]:
        """Return readiness metadata without exposing the internal weights path."""
        return {
            "ready": self._model is not None,
            "weights": Path(self._settings.weights_path).name,
            "device": "cpu",
            "classes": dict(self._classes or {}),
        }

    def predict(
        self,
        decoded: DecodedImage,
        source_name: str,
        options: PredictionOptions,
        request_id: str | None = None,
    ) -> PredictionResponse:
        """Run inference on normalized source pixels and build a shared response."""
        if self._model is None:
            raise ModelNotReadyError("The model is not ready.")

        started = time.perf_counter()
        try:
            results = self._model.predict(
                source=decoded.image,
                imgsz=options.image_size,
                conf=options.confidence,
                device="cpu",
                verbose=False,
            )
            inference_ms = (time.perf_counter() - started) * 1000
            detections = _convert_detections(results)
        except Exception as error:
            raise InferenceFailedError() from error

        return PredictionResponse(
            request_id=request_id or str(uuid4()),
            source_name=source_name,
            image=ImageInfo(width=decoded.width, height=decoded.height),
            model=ModelInfo(
                weights=Path(self._settings.weights_path).name,
                device="cpu",
                imgsz=options.image_size,
                confidence_threshold=options.confidence,
            ),
            detections=detections,
            detection_count=len(detections),
            inference_ms=round(inference_ms, 1),
        )


def _normalize_names(names: object) -> dict[int, str]:
    if isinstance(names, Mapping):
        normalized = {int(class_id): str(name) for class_id, name in names.items()}
        if len(normalized) != len(names):
            raise ValueError("duplicate normalized class IDs")
        return normalized
    if isinstance(names, Sequence) and not isinstance(names, (str, bytes)):
        return {class_id: str(name) for class_id, name in enumerate(names)}
    raise ValueError("unsupported model class names")


def _convert_detections(results: Any) -> list[Detection]:
    result_list = list(results)
    boxes = [] if not result_list else result_list[0].boxes
    converted: list[tuple[float, Detection]] = []

    for box in boxes:
        class_id = int(box.cls.item())
        confidence = float(box.conf.item())
        coordinates = box.xyxy.tolist()
        if len(coordinates) == 1 and isinstance(coordinates[0], list):
            coordinates = coordinates[0]
        x1, y1, x2, y2 = (float(value) for value in coordinates)
        converted.append(
            (
                confidence,
                Detection(
                    class_id=class_id,
                    class_name=_EXPECTED_CLASSES[class_id],
                    confidence=round(confidence, 6),
                    bbox=BoundingBox(
                        x1=round(x1, 2),
                        y1=round(y1, 2),
                        x2=round(x2, 2),
                        y2=round(y2, 2),
                    ),
                ),
            )
        )

    converted.sort(key=lambda item: item[0], reverse=True)
    return [detection for _, detection in converted]
