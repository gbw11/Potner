from pathlib import Path
import tomllib

import pytest
import yaml

from app.config import SETTINGS_ENV_NAMES, PredictionOptions, Settings
from app.errors import ConfigurationError
from app.schemas import ModelInfo


PROJECT_ROOT = Path(__file__).parents[1]


def _constraints() -> dict[str, str]:
    result = {}
    for line in (
        PROJECT_ROOT / "constraints-linux-cpu.txt"
    ).read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        name, version = stripped.split("==", maxsplit=1)
        result[name.casefold()] = version
    return result


def test_runtime_metadata_declares_image_and_inference_dependencies():
    pyproject_path = PROJECT_ROOT / "pyproject.toml"
    with pyproject_path.open("rb") as pyproject_file:
        dependencies = tomllib.load(pyproject_file)["project"]["dependencies"]

    assert "Pillow>=10.2,<12" in dependencies
    assert "ultralytics>=8.4.104,<9" in dependencies


def test_package_discovery_excludes_model_artifacts():
    with (PROJECT_ROOT / "pyproject.toml").open("rb") as pyproject_file:
        setuptools = tomllib.load(pyproject_file)["tool"]["setuptools"]

    assert setuptools["packages"]["find"] == {
        "include": ["app*"],
        "exclude": ["models*"],
    }
    assert "package-data" not in setuptools


def test_linux_cpu_constraints_pin_required_runtime_closure():
    constraints = _constraints()

    assert constraints | {
        "fastapi": "0.115.14",
        "python-multipart": "0.0.32",
        "pydantic": "2.12.5",
        "typer": "0.25.1",
        "uvicorn": "0.51.0",
        "pillow": "11.3.0",
        "ultralytics": "8.4.104",
        "opencv-python": "5.0.0.93",
        "numpy": "2.3.5",
        "torch": "2.5.1+cpu",
        "torchvision": "0.20.1+cpu",
    } == constraints


def test_dockerfile_pins_cpu_runtime_and_runs_unprivileged():
    dockerfile = (PROJECT_ROOT / "Dockerfile").read_text()

    assert dockerfile.startswith("FROM python:3.11.9-slim-bookworm\n")
    assert "torch==2.5.1+cpu" in dockerfile
    assert "torchvision==0.20.1+cpu" in dockerfile
    cpu_index = "https://download.pytorch.org/whl/cpu"
    assert cpu_index in dockerfile
    assert dockerfile.index(cpu_index) < dockerfile.rindex(
        "pip install --no-cache-dir"
    )
    assert "COPY --chown=app:app constraints-linux-cpu.txt ./" in dockerfile
    assert dockerfile.count("--constraint constraints-linux-cpu.txt") >= 2
    assert "libgl1" in dockerfile
    assert "libglib2.0-0" in dockerfile
    assert "--no-install-recommends" in dockerfile
    assert "rm -rf /var/lib/apt/lists/*" in dockerfile
    assert "import cv2, torch, ultralytics" in dockerfile
    assert "torch.version.cuda is None" in dockerfile
    assert "not torch.cuda.is_available()" in dockerfile
    assert ".[dev]" not in dockerfile
    assert "\nUSER " in dockerfile
    assert '"app.api:app"' in dockerfile
    assert '"--host", "0.0.0.0"' in dockerfile
    assert '"--port", "8000"' in dockerfile
    assert '"--workers", "1"' in dockerfile
    assert "HEALTHCHECK" in dockerfile
    assert "/health" in dockerfile


def test_compose_defines_hardened_single_service_runtime():
    compose = yaml.safe_load((PROJECT_ROOT / "docker-compose.yml").read_text())

    assert "version" not in compose
    assert list(compose["services"]) == ["yolo"]
    service = compose["services"]["yolo"]
    assert service["build"] == "."
    assert service["ports"] == ["8000:8000"]
    assert service["init"] is True
    assert service["restart"] == "unless-stopped"
    assert "/health" in " ".join(service["healthcheck"]["test"])

    model_mount = next(
        volume
        for volume in service["volumes"]
        if volume["target"] == "/app/models/best.pt"
    )
    assert model_mount == {
        "type": "bind",
        "source": "./models/best.pt",
        "target": "/app/models/best.pt",
        "read_only": True,
    }


def test_container_environment_example_matches_runtime_defaults():
    expected = {
        "YOLO_WEIGHTS_PATH": "/app/models/best.pt",
        "YOLO_CONFIDENCE": "0.10",
        "YOLO_IMAGE_SIZE": "640",
        "YOLO_MAX_UPLOAD_MIB": "10",
        "YOLO_MAX_CONCURRENCY": "1",
        "YOLO_LOG_LEVEL": "INFO",
    }
    env_values = dict(
        line.split("=", maxsplit=1)
        for line in (PROJECT_ROOT / ".env.example").read_text().splitlines()
        if line and not line.startswith("#")
    )
    compose = yaml.safe_load((PROJECT_ROOT / "docker-compose.yml").read_text())

    assert env_values == expected
    assert compose["services"]["yolo"]["environment"] == expected


def test_dockerignore_excludes_development_artifacts_but_keeps_model():
    patterns = set((PROJECT_ROOT / ".dockerignore").read_text().splitlines())

    assert {"__pycache__/", ".pytest_cache/", "tests/", "*.pyc"} <= patterns
    assert ".git/" in patterns
    assert "models/" not in patterns
    assert "models/best.pt" not in patterns


def test_settings_defaults(monkeypatch):
    for name in SETTINGS_ENV_NAMES:
        monkeypatch.delenv(name, raising=False)

    settings = Settings.from_env()

    assert settings.weights_path == "/app/models/best.pt"
    assert settings.confidence == 0.10
    assert settings.image_size == 640
    assert settings.max_upload_bytes == 10 * 1024 * 1024
    assert settings.max_concurrency == 1
    assert settings.log_level == "INFO"
    assert settings.device == "cpu"


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("YOLO_CONFIDENCE", "1.1"),
        ("YOLO_IMAGE_SIZE", "16"),
        ("YOLO_MAX_UPLOAD_MIB", "0"),
        ("YOLO_MAX_CONCURRENCY", "0"),
    ],
)
def test_invalid_environment_fails(monkeypatch, name, value):
    monkeypatch.setenv(name, value)

    with pytest.raises(ConfigurationError):
        Settings.from_env()


def test_settings_reads_all_supported_environment_variables(monkeypatch):
    monkeypatch.setenv("YOLO_WEIGHTS_PATH", "/models/custom.pt")
    monkeypatch.setenv("YOLO_CONFIDENCE", "0.25")
    monkeypatch.setenv("YOLO_IMAGE_SIZE", "1024")
    monkeypatch.setenv("YOLO_MAX_UPLOAD_MIB", "12")
    monkeypatch.setenv("YOLO_MAX_CONCURRENCY", "2")
    monkeypatch.setenv("YOLO_LOG_LEVEL", "debug")

    settings = Settings.from_env()

    assert settings.weights_path == "/models/custom.pt"
    assert settings.confidence == 0.25
    assert settings.image_size == 1024
    assert settings.max_upload_bytes == 12 * 1024 * 1024
    assert settings.max_concurrency == 2
    assert settings.log_level == "DEBUG"


@pytest.mark.parametrize(
    ("confidence", "image_size"),
    [(0.0, 32), (1.0, 4096)],
)
def test_prediction_options_accepts_boundary_values(confidence, image_size):
    options = PredictionOptions(confidence=confidence, image_size=image_size)

    assert options.confidence == confidence
    assert options.image_size == image_size


@pytest.mark.parametrize(
    ("confidence", "image_size"),
    [(-0.01, 640), (1.01, 640), (0.10, 31), (0.10, 4097)],
)
def test_prediction_options_rejects_values_outside_supported_ranges(
    confidence, image_size
):
    with pytest.raises(ValueError):
        PredictionOptions(confidence=confidence, image_size=image_size)


def test_settings_rejects_direct_construction_with_non_cpu_device():
    with pytest.raises(ValueError):
        Settings(
            weights_path="/models/best.pt",
            confidence=0.10,
            image_size=640,
            max_upload_bytes=10 * 1024 * 1024,
            max_concurrency=1,
            log_level="INFO",
            device="cuda",
        )


def test_model_info_rejects_non_cpu_device():
    with pytest.raises(ValueError):
        ModelInfo(
            weights="best.pt",
            device="cuda",
            imgsz=640,
            confidence_threshold=0.10,
        )
