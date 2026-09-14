"""Validated, immutable settings for CPU-only inference."""

from dataclasses import dataclass
import os
from typing import Literal

from .errors import ConfigurationError


SETTINGS_ENV_NAMES = (
    "YOLO_WEIGHTS_PATH",
    "YOLO_CONFIDENCE",
    "YOLO_IMAGE_SIZE",
    "YOLO_MAX_UPLOAD_MIB",
    "YOLO_MAX_CONCURRENCY",
    "YOLO_LOG_LEVEL",
)

_VALID_LOG_LEVELS = {"CRITICAL", "ERROR", "WARNING", "INFO", "DEBUG"}


@dataclass(frozen=True)
class PredictionOptions:
    confidence: float
    image_size: int

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("confidence must be between 0.0 and 1.0")
        if not 32 <= self.image_size <= 4096:
            raise ValueError("image_size must be between 32 and 4096")


@dataclass(frozen=True)
class Settings:
    weights_path: str
    confidence: float
    image_size: int
    max_upload_bytes: int
    max_concurrency: int
    log_level: str
    device: Literal["cpu"] = "cpu"

    def __post_init__(self) -> None:
        if self.device != "cpu":
            raise ValueError("device must be cpu")

    @classmethod
    def from_env(cls) -> "Settings":
        weights_path = os.environ.get("YOLO_WEIGHTS_PATH", "/app/models/best.pt")
        if not weights_path:
            raise ConfigurationError("YOLO_WEIGHTS_PATH must not be empty")

        confidence = _parse_float("YOLO_CONFIDENCE", "0.10")
        image_size = _parse_int("YOLO_IMAGE_SIZE", "640")
        max_upload_mib = _parse_int("YOLO_MAX_UPLOAD_MIB", "10")
        max_concurrency = _parse_int("YOLO_MAX_CONCURRENCY", "1")
        log_level = os.environ.get("YOLO_LOG_LEVEL", "INFO").upper()

        try:
            PredictionOptions(confidence=confidence, image_size=image_size)
        except ValueError as error:
            raise ConfigurationError(str(error)) from error
        if max_upload_mib <= 0:
            raise ConfigurationError("YOLO_MAX_UPLOAD_MIB must be greater than zero")
        if max_concurrency <= 0:
            raise ConfigurationError("YOLO_MAX_CONCURRENCY must be greater than zero")
        if log_level not in _VALID_LOG_LEVELS:
            raise ConfigurationError("YOLO_LOG_LEVEL must be a valid logging level")

        return cls(
            weights_path=weights_path,
            confidence=confidence,
            image_size=image_size,
            max_upload_bytes=max_upload_mib * 1024 * 1024,
            max_concurrency=max_concurrency,
            log_level=log_level,
        )


def _parse_float(name: str, default: str) -> float:
    value = os.environ.get(name, default)
    try:
        return float(value)
    except ValueError as error:
        raise ConfigurationError(f"{name} must be a number") from error


def _parse_int(name: str, default: str) -> int:
    value = os.environ.get(name, default)
    try:
        return int(value)
    except ValueError as error:
        raise ConfigurationError(f"{name} must be an integer") from error
