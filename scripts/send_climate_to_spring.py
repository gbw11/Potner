#!/usr/bin/env python3
"""DHT11 온습도 1회 읽기 → JSON → Spring POST.

  # 기본 (config의 spring.url 사용)
  python send_climate_to_spring.py --config config/raspberry_pi.yaml

  # URL 직접 지정
  python scripts/send_climate_to_spring.py --url http://192.168.x.x:8080/api/sensors/climate

  # 전송 없이 JSON만 출력
  python scripts/send_climate_to_spring.py --dry-run
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from src.config import load_config
from src.integrations import post_json

IIO_DEVICE = Path("/sys/bus/iio/devices/iio:device0")


def read_iio(name: str) -> float | None:
    path = IIO_DEVICE / name
    for _ in range(5):
        try:
            return int(path.read_text().strip()) / 1000.0
        except (OSError, ValueError):
            time.sleep(0.5)
    return None


def read_climate() -> dict[str, float | None]:
    if not IIO_DEVICE.exists():
        raise SystemExit(
            "IIO DHT11 장치가 없습니다. "
            "sudo dtoverlay dht11 gpiopin=21 후 다시 시도하세요."
        )
    return {
        "temperature_c": read_iio("in_temp_input"),
        "humidity_pct": read_iio("in_humidityrelative_input"),
    }


def build_payload(climate: dict[str, float | None]) -> dict:
    return {
        "timestamp": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "device": socket.gethostname(),
        "sensor": "dht11",
        "temperature_c": climate["temperature_c"],
        "humidity_pct": climate["humidity_pct"],
    }


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Send DHT11 climate JSON to Spring")
    p.add_argument("--config", default="config/raspberry_pi.yaml")
    p.add_argument("--url", default=None, help="Spring POST URL (overrides config)")
    p.add_argument("--dry-run", action="store_true", help="Print JSON only, do not POST")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    config = load_config(config_path) if config_path.exists() else {}
    spring_cfg = config.get("spring", {}) or {}

    url = args.url or spring_cfg.get("url") or ""
    climate = read_climate()
    payload = build_payload(climate)

    print("payload:")
    print(json.dumps(payload, ensure_ascii=False, indent=2))

    if args.dry_run:
        print("\n(dry-run: POST skipped)")
        return 0

    if not url:
        print(
            "\nSpring URL이 없습니다. config의 spring.url 또는 --url 을 지정하세요.",
            file=sys.stderr,
        )
        return 2

    print(f"\nPOST {url}")
    try:
        status, body = post_json(url, payload)
    except Exception as exc:  # noqa: BLE001
        print(f"전송 실패: {exc}", file=sys.stderr)
        return 1

    print(f"status: {status}")
    if body:
        print(f"response: {body[:500]}")
    return 0 if 200 <= status < 300 else 1


if __name__ == "__main__":
    raise SystemExit(main())
