import json
import os
from pathlib import Path
import subprocess
import sys

from PIL import Image
import pytest
from typer.testing import CliRunner

from app.schemas import ImageInfo, ModelInfo, PredictionResponse


def _write_jpeg(path: Path) -> None:
    Image.new("RGB", (8, 6), color="green").save(path, format="JPEG")


def _run_module(*arguments: str) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path(__file__).parents[1])
    return subprocess.run(
        [sys.executable, "-m", "app.cli", *arguments],
        cwd=Path(__file__).parents[1],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )


def _response(source_name: str, weights: str, confidence: float, imgsz: int):
    return PredictionResponse(
        request_id="test-request",
        source_name=source_name,
        image=ImageInfo(width=8, height=6),
        model=ModelInfo(
            weights=Path(weights).name,
            imgsz=imgsz,
            confidence_threshold=confidence,
        ),
        detections=[],
        detection_count=0,
        inference_ms=1.0,
    )


class RecordingPredictor:
    instances = []
    load_error = None
    prediction_errors = {}

    def __init__(self, settings):
        self.settings = settings
        self.load_calls = 0
        self.predict_calls = []
        type(self).instances.append(self)

    def load(self):
        self.load_calls += 1
        if type(self).load_error is not None:
            raise type(self).load_error

    def predict(self, decoded, source_name, options):
        self.predict_calls.append((decoded, source_name, options))
        error = type(self).prediction_errors.get(source_name)
        if error is not None:
            raise error
        return _response(
            source_name,
            self.settings.weights_path,
            options.confidence,
            options.image_size,
        )


def _runner(monkeypatch):
    from app import cli

    RecordingPredictor.instances = []
    RecordingPredictor.load_error = None
    RecordingPredictor.prediction_errors = {}
    monkeypatch.setattr(cli, "YoloPredictor", RecordingPredictor)
    return CliRunner(), cli.app


def test_predict_prints_only_one_json_document_to_stdout(monkeypatch, tmp_path):
    image_path = tmp_path / "leaf.jpg"
    _write_jpeg(image_path)
    runner, app = _runner(monkeypatch)

    result = runner.invoke(
        app,
        [
            "predict",
            str(image_path),
            "--weights",
            "/models/custom.pt",
            "--conf",
            "0.25",
            "--imgsz",
            "320",
        ],
    )

    assert result.exit_code == 0
    assert result.stderr == ""
    assert len(result.stdout.splitlines()) == 1
    payload = json.loads(result.stdout)
    assert payload["source_name"] == image_path.name
    assert payload["model"] == {
        "weights": "custom.pt",
        "device": "cpu",
        "imgsz": 320,
        "confidence_threshold": 0.25,
    }
    predictor = RecordingPredictor.instances[0]
    assert predictor.load_calls == 1
    assert len(predictor.predict_calls) == 1
    assert predictor.settings.device == "cpu"


def test_predict_invalid_image_is_structured_json_with_exit_four(tmp_path):
    image_path = tmp_path / "broken.jpg"
    image_path.write_bytes(b"not an image")
    result = _run_module("predict", str(image_path))

    assert result.returncode == 4
    assert result.stderr == ""
    assert json.loads(result.stdout) == {
        "error": {
            "code": "INVALID_IMAGE",
            "message": "Unable to decode image.",
            "request_id": None,
        }
    }


@pytest.mark.parametrize(
    "arguments",
    [
        (),
        ("predict",),
        ("predict", "image.jpg", "--conf", "not-a-float"),
        ("batch", "."),
    ],
    ids=["no-command", "missing-image", "invalid-conf", "missing-output"],
)
def test_module_usage_errors_print_exactly_one_invalid_request_json(arguments):
    result = _run_module(*arguments)

    assert result.returncode == 2
    assert len(result.stdout.splitlines()) == 1
    payload = json.loads(result.stdout)
    assert payload["error"]["code"] == "INVALID_REQUEST"
    assert payload["error"]["message"]
    assert payload["error"]["request_id"] is None


def test_batch_recurses_in_sorted_order_and_returns_six_for_partial_failure(
    monkeypatch, tmp_path
):
    from app.errors import InvalidImageError

    image_dir = tmp_path / "images"
    nested = image_dir / "nested"
    nested.mkdir(parents=True)
    _write_jpeg(nested / "b.JPG")
    _write_jpeg(image_dir / "a.png")
    (image_dir / "ignore.txt").write_text("ignored")
    runner, app = _runner(monkeypatch)
    RecordingPredictor.prediction_errors = {"b.JPG": InvalidImageError()}
    output = tmp_path / "results.jsonl"

    result = runner.invoke(
        app, ["batch", str(image_dir), "--output", str(output)]
    )

    assert result.exit_code == 6
    assert result.stdout == ""
    assert result.stderr == ""
    records = [json.loads(line) for line in output.read_text().splitlines()]
    assert len(records) == 2
    assert records[0]["source_name"] == "a.png"
    assert records[1] == {
        "error": {
            "code": "INVALID_IMAGE",
            "message": "Unable to decode image.",
            "request_id": None,
        }
    }
    predictor = RecordingPredictor.instances[0]
    assert predictor.load_calls == 1
    assert [call[1] for call in predictor.predict_calls] == ["a.png", "b.JPG"]


@pytest.mark.parametrize("alias_output", [False, True], ids=["direct", "symlink"])
def test_batch_rejects_output_aliasing_an_input_without_changing_input_bytes(
    monkeypatch, tmp_path, alias_output
):
    image_dir = tmp_path / "images"
    image_dir.mkdir()
    image_path = image_dir / "leaf.jpg"
    _write_jpeg(image_path)
    original_bytes = image_path.read_bytes()
    output = image_path
    if alias_output:
        output = image_dir / "results.jsonl"
        output.symlink_to(image_path.name)
    runner, app = _runner(monkeypatch)

    result = runner.invoke(
        app, ["batch", str(image_dir), "--output", str(output)]
    )

    assert result.exit_code == 2
    assert json.loads(result.stdout)["error"]["code"] == "INVALID_REQUEST"
    assert image_path.read_bytes() == original_bytes


def test_predict_maps_invalid_options_to_exit_two(monkeypatch, tmp_path):
    image_path = tmp_path / "leaf.webp"
    Image.new("RGB", (8, 6), color="green").save(image_path, format="WEBP")
    runner, app = _runner(monkeypatch)

    result = runner.invoke(app, ["predict", str(image_path), "--conf", "1.1"])

    assert result.exit_code == 2
    assert json.loads(result.stdout)["error"]["code"] == "INVALID_REQUEST"


def test_predict_maps_model_load_failure_to_exit_three(monkeypatch, tmp_path):
    from app.errors import ModelNotReadyError

    image_path = tmp_path / "leaf.webp"
    Image.new("RGB", (8, 6), color="green").save(image_path, format="WEBP")
    runner, app = _runner(monkeypatch)
    RecordingPredictor.load_error = ModelNotReadyError("model unavailable")
    result = runner.invoke(app, ["predict", str(image_path)])

    assert result.exit_code == 3
    assert json.loads(result.stdout)["error"]["code"] == "MODEL_NOT_READY"


def test_predict_maps_inference_failure_to_exit_five(monkeypatch, tmp_path):
    from app.errors import InferenceFailedError

    image_path = tmp_path / "leaf.webp"
    Image.new("RGB", (8, 6), color="green").save(image_path, format="WEBP")
    runner, app = _runner(monkeypatch)
    RecordingPredictor.prediction_errors = {
        image_path.name: InferenceFailedError()
    }
    result = runner.invoke(app, ["predict", str(image_path)])

    assert result.exit_code == 5
    assert json.loads(result.stdout)["error"]["code"] == "INFERENCE_FAILED"
