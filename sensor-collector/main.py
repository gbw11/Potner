from __future__ import annotations

import argparse
import signal
import sys
import time
from pathlib import Path

from src.collector import Collector
from src.config import load_config
from src.device import resolve_device_id
from src.mqtt import build_mqtt_publisher
from src.transport import build_spring_publisher


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Collect plant/environment sensor data")
    parser.add_argument(
        "--config",
        default="config/default.yaml",
        help="Path to YAML config (default: config/default.yaml)",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Read sensors once and exit",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"Config not found: {config_path}", file=sys.stderr)
        return 1

    config = load_config(config_path)
    device_id = resolve_device_id((config.get("mqtt") or {}).get("device_id"))
    collector = Collector(config)
    publisher = build_spring_publisher(config)
    mqtt_publisher = build_mqtt_publisher(config)
    running = True

    def _stop(_signum, _frame) -> None:
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    interval = float(config.get("interval_sec", 5))
    mqtt_note = (
        f"on {mqtt_publisher.host}:{mqtt_publisher.port}"
        if mqtt_publisher.enabled
        else "off"
    )
    print(
        f"platform={config.get('platform')} device_id={device_id} interval={interval}s "
        f"storage={config.get('storage', {}).get('path')} "
        f"backend={'on ' + publisher.url if publisher.enabled else 'off'} "
        f"mqtt={mqtt_note}"
    )

    try:
        while running:
            row = collector.read_once()
            row["device_id"] = device_id
            pub = publisher.publish(row)
            pub_note = "skip" if not publisher.enabled else ("ok" if pub.ok else pub.message)
            mqtt = mqtt_publisher.publish(row)
            mqtt_status = (
                "skip"
                if not mqtt_publisher.enabled
                else (f"ok({mqtt.count})" if mqtt.ok else mqtt.message)
            )
            print(
                f"[{row['timestamp']}] device={device_id} "
                f"T={row['temperature_c']}C H={row['humidity_pct']}% "
                f"Lux={row['light_lux']} Soil={row['soil_moisture_pct']}% "
                f"(raw={row['soil_raw']}) | spring={pub_note} mqtt={mqtt_status}"
            )
            if args.once:
                break
            end = time.time() + interval
            while running and time.time() < end:
                time.sleep(0.2)
    finally:
        mqtt_publisher.close()
        collector.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
