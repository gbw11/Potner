from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

from ..device import resolve_device_id


@dataclass(frozen=True)
class PublishResult:
    ok: bool
    status_code: Optional[int]
    message: str


class SpringSoilPublisher:
    """
    토양수분 측정값을 Spring Boot로 POST.

    급수량 계산은 서버 책임. Pi는 deviceId + raw/% 만 넘긴다.
    """

    def __init__(
        self,
        base_url: str,
        path: str = "/api/v1/sensors/soil",
        *,
        device_id: str = "unknown-device",
        timeout_sec: float = 5.0,
        enabled: bool = True,
    ) -> None:
        self.enabled = enabled
        self.base_url = base_url.rstrip("/")
        self.path = path if path.startswith("/") else f"/{path}"
        self.device_id = device_id
        self.timeout_sec = timeout_sec

    @property
    def url(self) -> str:
        return f"{self.base_url}{self.path}"

    def build_payload(self, reading: dict[str, Any]) -> dict[str, Any]:
        return {
            "deviceId": str(reading.get("device_id") or self.device_id),
            "timestamp": reading.get("timestamp"),
            "soilRaw": reading.get("soil_raw"),
            "soilMoisturePct": reading.get("soil_moisture_pct"),
        }

    def publish(self, reading: dict[str, Any]) -> PublishResult:
        if not self.enabled:
            return PublishResult(ok=True, status_code=None, message="disabled")

        payload = self.build_payload(reading)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            self.url,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                status = getattr(resp, "status", 200)
                return PublishResult(ok=True, status_code=status, message="ok")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:200]
            return PublishResult(
                ok=False,
                status_code=exc.code,
                message=f"HTTP {exc.code}: {detail or exc.reason}",
            )
        except Exception as exc:  # noqa: BLE001 — 수집 루프는 계속 돌아야 함
            return PublishResult(ok=False, status_code=None, message=str(exc))


def build_spring_publisher(config: dict[str, Any]) -> SpringSoilPublisher:
    cfg = config.get("backend", {}) or {}
    base_url = str(
        os.getenv("SPRING_BASE_URL")
        or cfg.get("base_url")
        or "http://127.0.0.1:8080"
    )
    return SpringSoilPublisher(
        base_url=base_url,
        path=str(cfg.get("soil_path", "/api/v1/sensors/soil")),
        device_id=resolve_device_id(cfg.get("device_id")),
        timeout_sec=float(cfg.get("timeout_sec", 5)),
        enabled=bool(cfg.get("enabled", False)),
    )
