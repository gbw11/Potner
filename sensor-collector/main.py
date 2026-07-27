from __future__ import annotations

import argparse
import signal
import sys
import time
from pathlib import Path

from src.collector import Collector
from src.config import load_config
from src.events.detector import EventDetector
from src.events.store import EventStore
from src.status.rules import evaluate_status
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
    collector = Collector(config)
    publisher = build_spring_publisher(config)
    events_cfg = config.get("events", {}) or {}
    detector = EventDetector(EventStore(events_cfg.get("path", "data/events.jsonl")))
    running = True

    def _stop(_signum, _frame) -> None:
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    interval = float(config.get("interval_sec", 5))
    print(
        f"platform={config.get('platform')} interval={interval}s "
        f"storage={config.get('storage', {}).get('path')} "
        f"backend={'on ' + publisher.url if publisher.enabled else 'off'}"
    )

    try:
        while running:
            row = collector.read_once()
            status = evaluate_status(row, config.get("status_rules"))
            events = detector.update(status)
            pub = publisher.publish(row)
            pub_note = "skip" if not publisher.enabled else ("ok" if pub.ok else pub.message)
            print(
                f"[{row['timestamp']}] "
                f"T={row['temperature_c']}C H={row['humidity_pct']}% "
                f"Lux={row['light_lux']} Soil={row['soil_moisture_pct']}% "
                f"(raw={row['soil_raw']}) | {status.summary_ko} | spring={pub_note}"
            )
            for event in events:
                if event.get("type") != "session_start":
                    print(f"  event: {event.get('message')}")
            if args.once:
                break
            end = time.time() + interval
            while running and time.time() < end:
                time.sleep(0.2)
    finally:
        collector.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
