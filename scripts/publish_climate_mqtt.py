#!/usr/bin/env python3
"""DHT11 온·습도 → potner MQTT 규약 publish.

센서(telemetry)는 SensorType 별로 메시지 1개씩:
  TEMPERATURE + CELSIUS
  HUMIDITY + PERCENT

  python publish_climate_mqtt.py
  python scripts/publish_climate_mqtt.py --yes
  python scripts/publish_climate_mqtt.py --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from src.config import load_config
from src.mqtt import publish_many, subscribe_once

IIO_DEVICE = Path("/sys/bus/iio/devices/iio:device0")


def utc_now_z() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def read_iio(name: str) -> float | None:
    path = IIO_DEVICE / name
    for _ in range(5):
        try:
            return int(path.read_text().strip()) / 1000.0
        except (OSError, ValueError):
            time.sleep(0.5)
    return None


def read_climate() -> tuple[float, float]:
    if not IIO_DEVICE.exists():
        raise SystemExit(
            "IIO DHT11 장치가 없습니다.\n"
            "  sudo dtoverlay dht11 gpiopin=21"
        )
    temp = read_iio("in_temp_input")
    hum = read_iio("in_humidityrelative_input")
    if temp is None or hum is None:
        raise SystemExit("온습도 값을 읽지 못했습니다.")
    return float(temp), float(hum)


def telemetry_payload(
    *,
    device_id: str,
    sensor_type: str,
    value: float,
    unit: str,
    measured_at: str,
) -> dict[str, Any]:
    return {
        "messageId": str(uuid.uuid4()),
        "deviceId": device_id,
        "sensorType": sensor_type,
        "value": round(value, 1),
        "unit": unit,
        "measuredAt": measured_at,
    }


def heartbeat_payload(*, device_id: str, sent_at: str) -> dict[str, Any]:
    return {
        "messageId": str(uuid.uuid4()),
        "deviceId": device_id,
        "sentAt": sent_at,
    }


def confirm_on_pi(temp: float, hum: float, messages: list[tuple[str, dict]], *, auto_yes: bool) -> bool:
    print("=" * 48)
    print("라즈베리파이 온·습도 확인 (potner MQTT)")
    print("=" * 48)
    print(f"  온도: {temp}°C  → TEMPERATURE / CELSIUS")
    print(f"  습도: {hum}%   → HUMIDITY / PERCENT")
    print("-" * 48)
    for topic, payload in messages:
        print(f"topic: {topic}")
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        print("-" * 48)
    print("=" * 48)

    if auto_yes:
        print("(--yes) 확인 생략 → 전송 진행")
        return True

    try:
        answer = input("이 값들을 MQTT 브로커로 보낼까요? [y/N]: ").strip().lower()
    except EOFError:
        print("입력 없음 → 전송 취소", file=sys.stderr)
        return False
    return answer in {"y", "yes"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Publish potner climate telemetry over MQTT")
    p.add_argument("--config", default="config/raspberry_pi.yaml")
    p.add_argument("--yes", "-y", action="store_true")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--no-verify", action="store_true")
    p.add_argument("--no-heartbeat", action="store_true")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    config = load_config(config_path) if config_path.exists() else {}
    mqtt_cfg = config.get("mqtt", {}) or {}

    host = str(mqtt_cfg.get("host", "127.0.0.1"))
    port = int(mqtt_cfg.get("port", 1883))
    qos = int(mqtt_cfg.get("qos", 1))
    device_id = str(mqtt_cfg.get("device_id", "raspberry-01"))
    username = mqtt_cfg.get("username") or os.environ.get("MQTT_USERNAME")
    password = (
        os.environ.get("MQTT_PASSWORD")
        or mqtt_cfg.get("password")
    )
    telemetry_topic = str(
        mqtt_cfg.get("telemetry_topic")
        or f"potner/device/{device_id}/sensor/telemetry"
    )
    heartbeat_topic = str(
        mqtt_cfg.get("heartbeat_topic")
        or f"potner/device/{device_id}/status/heartbeat"
    )

    temp, hum = read_climate()
    measured_at = utc_now_z()

    messages: list[tuple[str, dict[str, Any]]] = [
        (
            telemetry_topic,
            telemetry_payload(
                device_id=device_id,
                sensor_type="TEMPERATURE",
                value=temp,
                unit="CELSIUS",
                measured_at=measured_at,
            ),
        ),
        (
            telemetry_topic,
            telemetry_payload(
                device_id=device_id,
                sensor_type="HUMIDITY",
                value=hum,
                unit="PERCENT",
                measured_at=measured_at,
            ),
        ),
    ]
    if not args.no_heartbeat:
        messages.append(
            (
                heartbeat_topic,
                heartbeat_payload(device_id=device_id, sent_at=utc_now_z()),
            )
        )

    if not confirm_on_pi(temp, hum, messages, auto_yes=args.yes):
        print("전송 취소됨.")
        return 0

    if args.dry_run:
        print("(dry-run) MQTT publish 생략")
        return 0

    if not username or not password:
        print(
            "MQTT username/password 가 없습니다.\n"
            "config/raspberry_pi.yaml 의 mqtt.username/password 또는\n"
            "환경변수 MQTT_USERNAME / MQTT_PASSWORD 를 설정하세요.",
            file=sys.stderr,
        )
        return 2

    verified: list[str | None] = [None]
    verifier = None
    if not args.no_verify:
        def _listen() -> None:
            verified[0] = subscribe_once(
                host=host,
                port=port,
                topic=telemetry_topic,
                username=username,
                password=password,
                timeout=8.0,
            )

        verifier = threading.Thread(target=_listen, daemon=True)
        verifier.start()
        time.sleep(0.4)

    print(f"\nMQTT publish → {host}:{port}  qos={qos}")
    try:
        publish_many(
            host=host,
            port=port,
            messages=messages,
            username=username,
            password=password,
            qos=qos,
        )
    except Exception as exc:  # noqa: BLE001
        print(f"전송 실패: {exc}", file=sys.stderr)
        return 1

    print(f"publish OK  ({len(messages)} messages)")

    if verifier is not None:
        verifier.join(timeout=9.0)
        if verified[0]:
            print("브로커 수신 확인(telemetry):")
            print(verified[0])
        else:
            print(
                "경고: publish는 됐지만 구독 검증 메시지를 못 받았습니다. "
                "권한/토픽을 확인하세요.",
                file=sys.stderr,
            )
            return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
