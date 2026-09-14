"""Opt-in integration test for the approved YOLO weights on CPU."""

import os
from pathlib import Path

import pytest

from app.config import PredictionOptions, Settings
from app.images import decode_image_path
from app.inference import YoloPredictor


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_CLASSES = {
    0: "germination",
    1: "vegetative",
    2: "flowering",
}


@pytest.mark.smoke
def test_real_weights_predict_on_cpu():
    sample_value = os.environ.get("YOLO_SMOKE_IMAGE")
    if not sample_value:
        pytest.skip("YOLO_SMOKE_IMAGE is unset; skipping real-model CPU smoke test")

    sample_image = Path(sample_value).expanduser()
    if not sample_image.is_file():
        pytest.skip(
            f"YOLO_SMOKE_IMAGE does not exist or is not a file: {sample_image}"
        )

    settings = Settings(
        weights_path=str(PACKAGE_ROOT / "models" / "best.pt"),
        confidence=0.1,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )
    predictor = YoloPredictor(settings)

    predictor.load()
    assert predictor.ready_info()["classes"] == EXPECTED_CLASSES

    decoded = decode_image_path(sample_image)
    response = predictor.predict(
        decoded,
        sample_image.name,
        PredictionOptions(confidence=0.1, image_size=640),
    )

    assert response.model.device == "cpu"
    assert response.image.width > 0
    assert response.image.height > 0
    assert response.detection_count == len(response.detections)
