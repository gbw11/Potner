from pathlib import Path

from PIL import Image
import pytest

from app.config import PredictionOptions, Settings
from app.errors import InferenceFailedError, ModelNotReadyError
from app.images import DecodedImage
from app.inference import YoloPredictor
from fakes import FakeBox, FakeFactory, FakeModel


@pytest.fixture
def settings(tmp_path):
    weights = tmp_path / "plant-growth-best.pt"
    weights.write_bytes(b"local test checkpoint")
    return Settings(
        weights_path=str(weights),
        confidence=0.1,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )


@pytest.fixture
def decoded_image():
    image = Image.new("RGB", (300, 200), "green")
    return DecodedImage(image=image, width=300, height=200, format="JPEG")


def test_predictor_loads_one_shared_model_and_reports_safe_ready_info(settings):
    factory = FakeFactory(
        FakeModel(names=["germination", "vegetative", "flowering"])
    )
    predictor = YoloPredictor(settings, model_factory=factory)

    predictor.load()
    predictor.load()

    assert factory.paths == [settings.weights_path]
    assert predictor.ready_info() == {
        "ready": True,
        "weights": Path(settings.weights_path).name,
        "device": "cpu",
        "classes": {
            0: "germination",
            1: "vegetative",
            2: "flowering",
        },
    }


def test_predictor_forces_cpu_and_maps_sorted_rounded_boxes(
    settings, decoded_image
):
    model = FakeModel(
        boxes=[
            FakeBox(0, 0.33333359, [1.234, 2.345, 100.006, 120.005]),
            FakeBox(1, 0.98765451, [10.123, 20.126, 200.999, 190.994]),
        ]
    )
    factory = FakeFactory(model)
    predictor = YoloPredictor(settings, model_factory=factory)
    predictor.load()

    response = predictor.predict(
        decoded_image,
        "plant.jpg",
        PredictionOptions(confidence=0.1234567, image_size=896),
        "request-1",
    )

    assert model.predict_kwargs == {
        "source": decoded_image.image,
        "imgsz": 896,
        "conf": 0.1234567,
        "device": "cpu",
        "verbose": False,
    }
    assert response.request_id == "request-1"
    assert response.source_name == "plant.jpg"
    assert response.image.model_dump() == {"width": 300, "height": 200}
    assert response.model.model_dump() == {
        "weights": "plant-growth-best.pt",
        "device": "cpu",
        "imgsz": 896,
        "confidence_threshold": 0.1234567,
    }
    assert [d.class_name for d in response.detections] == [
        "vegetative",
        "germination",
    ]
    assert response.detections[0].confidence == 0.987655
    assert response.detections[0].bbox.model_dump() == {
        "x1": 10.12,
        "y1": 20.13,
        "x2": 201.0,
        "y2": 190.99,
    }
    assert response.detection_count == 2
    assert response.inference_ms == round(response.inference_ms, 1)
    assert response.inference_ms >= 0.0


def test_predict_generates_request_id_and_handles_no_boxes(settings, decoded_image):
    predictor = YoloPredictor(settings, model_factory=FakeFactory())
    predictor.load()

    response = predictor.predict(
        decoded_image,
        "empty.png",
        PredictionOptions(confidence=0.1, image_size=640),
    )

    assert response.request_id
    assert response.detections == []
    assert response.detection_count == 0


@pytest.mark.parametrize(
    "names",
    [
        {0: "seedling", 1: "vegetative", 2: "flowering"},
        {"0": "germination", "1": "vegetative"},
    ],
)
def test_load_rejects_unexpected_classes(settings, names):
    predictor = YoloPredictor(
        settings,
        model_factory=FakeFactory(FakeModel(names=names)),
    )

    with pytest.raises(ModelNotReadyError):
        predictor.load()

    assert predictor.ready_info()["ready"] is False


def test_load_wraps_model_factory_failure(settings):
    predictor = YoloPredictor(
        settings,
        model_factory=FakeFactory(load_error=OSError("missing weights")),
    )

    with pytest.raises(ModelNotReadyError):
        predictor.load()


def test_load_rejects_missing_path_without_calling_model_factory(tmp_path):
    settings = Settings(
        weights_path=str(tmp_path / "missing.pt"),
        confidence=0.1,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )
    factory = FakeFactory()
    predictor = YoloPredictor(settings, model_factory=factory)

    with pytest.raises(ModelNotReadyError):
        predictor.load()

    assert factory.paths == []


def test_load_rejects_directory_without_calling_model_factory(tmp_path):
    settings = Settings(
        weights_path=str(tmp_path),
        confidence=0.1,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )
    factory = FakeFactory()
    predictor = YoloPredictor(settings, model_factory=factory)

    with pytest.raises(ModelNotReadyError):
        predictor.load()

    assert factory.paths == []


def test_load_rejects_remote_reference_without_calling_model_factory():
    settings = Settings(
        weights_path="https://models.example.invalid/best.pt",
        confidence=0.1,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )
    factory = FakeFactory()
    predictor = YoloPredictor(settings, model_factory=factory)

    with pytest.raises(ModelNotReadyError):
        predictor.load()

    assert factory.paths == []


def test_load_allows_symlink_to_local_file(tmp_path):
    target = tmp_path / "approved.pt"
    target.write_bytes(b"local test checkpoint")
    link = tmp_path / "current.pt"
    link.symlink_to(target)
    settings = Settings(
        weights_path=str(link),
        confidence=0.1,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )
    factory = FakeFactory()
    predictor = YoloPredictor(settings, model_factory=factory)

    predictor.load()

    assert factory.paths == [str(link)]


def test_predict_requires_a_ready_model(settings, decoded_image):
    predictor = YoloPredictor(settings, model_factory=FakeFactory())

    with pytest.raises(ModelNotReadyError):
        predictor.predict(
            decoded_image,
            "plant.jpg",
            PredictionOptions(confidence=0.1, image_size=640),
        )


def test_predict_wraps_model_failure(settings, decoded_image):
    predictor = YoloPredictor(
        settings,
        model_factory=FakeFactory(
            FakeModel(prediction_error=RuntimeError("operator failed"))
        ),
    )
    predictor.load()

    with pytest.raises(InferenceFailedError):
        predictor.predict(
            decoded_image,
            "plant.jpg",
            PredictionOptions(confidence=0.1, image_size=640),
        )
