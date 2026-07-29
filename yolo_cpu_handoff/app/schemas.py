"""Shared JSON response models for CLI and HTTP inference results."""

from typing import Literal

from pydantic import BaseModel


class BoundingBox(BaseModel):
    x1: float
    y1: float
    x2: float
    y2: float


class Detection(BaseModel):
    class_id: int
    class_name: str
    confidence: float
    bbox: BoundingBox


class ImageInfo(BaseModel):
    width: int
    height: int


class ModelInfo(BaseModel):
    weights: str
    device: Literal["cpu"] = "cpu"
    imgsz: int
    confidence_threshold: float


class PredictionResponse(BaseModel):
    request_id: str
    source_name: str
    image: ImageInfo
    model: ModelInfo
    detections: list[Detection]
    detection_count: int
    inference_ms: float


class ErrorBody(BaseModel):
    code: str
    message: str
    request_id: str | None = None


class ErrorResponse(BaseModel):
    error: ErrorBody
