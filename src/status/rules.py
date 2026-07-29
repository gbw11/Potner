from __future__ import annotations

from typing import Any, Optional

from .models import MetricLevel, PlantStatus


_LEVEL_KO = {
    "low": "낮음",
    "normal": "정상",
    "high": "높음",
    "unknown": "알 수 없음",
}


def _level_for(value: Optional[float], low: float, high: float) -> str:
    if value is None:
        return "unknown"
    if value < low:
        return "low"
    if value > high:
        return "high"
    return "normal"


def _metric(
    name: str,
    value: Optional[float],
    low: float,
    high: float,
    *,
    low_ko: str,
    normal_ko: str,
    high_ko: str,
) -> MetricLevel:
    level = _level_for(value, low, high)
    labels = {"low": low_ko, "normal": normal_ko, "high": high_ko, "unknown": "알 수 없음"}
    return MetricLevel(name=name, value=value, level=level, label_ko=labels[level])


def evaluate_status(reading: dict[str, Any], rules: dict[str, Any] | None = None) -> PlantStatus:
    """원시 센서 reading → 구조화된 상태. LLM 호출 전에 로컬에서 판단."""
    rules = rules or {}
    soil_r = rules.get("soil_moisture_pct", {})
    temp_r = rules.get("temperature_c", {})
    hum_r = rules.get("humidity_pct", {})
    light_r = rules.get("light_lux", {})

    soil = _metric(
        "soil_moisture_pct",
        _as_float(reading.get("soil_moisture_pct")),
        float(soil_r.get("low", 30)),
        float(soil_r.get("high", 80)),
        low_ko="낮음(목마름)",
        normal_ko="정상",
        high_ko="높음(과습)",
    )
    temperature = _metric(
        "temperature_c",
        _as_float(reading.get("temperature_c")),
        float(temp_r.get("low", 15)),
        float(temp_r.get("high", 32)),
        low_ko="낮음(추움)",
        normal_ko="정상",
        high_ko="높음(더움)",
    )
    humidity = _metric(
        "humidity_pct",
        _as_float(reading.get("humidity_pct")),
        float(hum_r.get("low", 30)),
        float(hum_r.get("high", 80)),
        low_ko="낮음",
        normal_ko="정상",
        high_ko="높음",
    )
    light = _metric(
        "light_lux",
        _as_float(reading.get("light_lux")),
        float(light_r.get("low", 100)),
        float(light_r.get("high", 5000)),
        low_ko="부족",
        normal_ko="적당",
        high_ko="강함",
    )

    issues: list[str] = []
    if soil.level == "low":
        issues.append("토양이 말라 있어요")
    elif soil.level == "high":
        issues.append("물이 너무 많아요")
    if temperature.level == "low":
        issues.append("추워요")
    elif temperature.level == "high":
        issues.append("더워요")
    if light.level == "low":
        issues.append("빛이 부족해요")
    elif light.level == "high":
        issues.append("빛이 너무 세요")
    if humidity.level == "low":
        issues.append("공기가 건조해요")
    elif humidity.level == "high":
        issues.append("공기가 습해요")

    needs_attention = any(
        m.level in {"low", "high"} for m in (soil, temperature, humidity, light)
    )
    summary_ko = "지금 상태는 괜찮아요." if not issues else " / ".join(issues)

    return PlantStatus(
        timestamp=str(reading.get("timestamp", "")),
        soil=soil,
        temperature=temperature,
        humidity=humidity,
        light=light,
        summary_ko=summary_ko,
        needs_attention=needs_attention,
    )


def _as_float(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
