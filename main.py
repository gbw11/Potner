from __future__ import annotations

import argparse
import logging
import signal
import sys
import time
from pathlib import Path

from src.collector import Collector
from src.config import load_config
from src.device import resolve_device_id
from src.logging_setup import setup_logging
from src.mqtt import (
    build_capture_listener,
    build_fan_listener,
    build_mqtt_publisher,
    build_water_listener,
)
from src.transport import build_spring_publisher

log = logging.getLogger(__name__)


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
    setup_logging(config)
    device_id = resolve_device_id((config.get("mqtt") or {}).get("device_id"))
    collector = Collector(config)
    publisher = build_spring_publisher(config)
    mqtt_publisher = build_mqtt_publisher(config)
    # 과급수 게이트의 "이미 젖었으면 스킵" 판정용. 센서를 새로 열지 않고 collector 의
    # 마지막 측정값을 넘긴다 (같은 I2C 센서를 두 번 열면 안 되므로).
    last_row: dict = {}
    water_listener = (
        None
        if args.once
        else build_water_listener(
            config, soil_provider=lambda: last_row.get("soil_moisture_pct")
        )
    )
    capture_listener = (
        None
        if args.once
        else build_capture_listener(config, capture_fn=collector.capture_once)
    )
    fan_listener = None if args.once else build_fan_listener(config)
    running = True

    def _stop(_signum, _frame) -> None:
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    interval = float(config.get("interval_sec", 5))
    mqtt_note = "off"
    if mqtt_publisher.enabled:
        # 하트비트는 connect 시점부터 백그라운드로 발행된다. 여기서 실패해도
        # publish 루프가 매 주기 재접속을 시도하므로 수집은 계속한다.
        try:
            mqtt_publisher.connect()
            mqtt_note = (
                f"on {mqtt_publisher.host}:{mqtt_publisher.port} "
                f"hb={mqtt_publisher.heartbeat_interval_sec:.0f}s"
            )
        except Exception as exc:  # noqa: BLE001
            log.warning(f"mqtt connect failed (will retry): {exc}")
            mqtt_note = f"retrying {mqtt_publisher.host}:{mqtt_publisher.port}"
    water_note = "off"
    if water_listener is not None:
        try:
            water_listener.start()
            # 펌프 드라이버명을 배너에 남긴다 — 실기에서 mock 이 물려 있으면 바로 드러난다
            pump_name = type(water_listener.pump).__name__
            water_note = f"on {water_listener.command_topic} pump={pump_name}"
        except Exception as exc:  # noqa: BLE001 — 급수 실패해도 수집은 계속
            log.warning(f"water listener unavailable: {exc}")
            # 리스너를 버리기 전에 GPIO 를 놓아준다 (안 하면 핀이 잡힌 채로 남는다)
            try:
                water_listener.pump.close()
            except Exception:  # noqa: BLE001
                pass
            water_listener = None

    capture_note = "off"
    if capture_listener is not None:
        try:
            capture_listener.start()
            capture_note = f"on {capture_listener.command_topic}"
        except Exception as exc:  # noqa: BLE001 — 촬영 실패해도 수집은 계속
            log.warning(f"capture listener unavailable: {exc}")
            capture_listener = None

    fan_note = "off"
    if fan_listener is not None:
        try:
            fan_listener.start()
            # 팬 드라이버명을 배너에 남긴다 — 실기에서 mock 이 물려 있으면 바로 드러난다
            fan_name = type(fan_listener.fan).__name__
            fan_note = f"on {fan_listener.command_topic} fan={fan_name}"
        except Exception as exc:  # noqa: BLE001 — 팬 실패해도 수집은 계속
            log.warning(f"fan listener unavailable: {exc}")
            # 리스너를 버리기 전에 GPIO 를 놓아준다 (안 하면 핀이 잡힌 채로 남는다)
            try:
                fan_listener.fan.close()
            except Exception:  # noqa: BLE001
                pass
            fan_listener = None

    log.info(
        f"platform={config.get('platform')} device_id={device_id} interval={interval}s "
        f"storage={config.get('storage', {}).get('path')} "
        f"backend={'on ' + publisher.url if publisher.enabled else 'off'} "
        f"mqtt={mqtt_note} water={water_note} capture={capture_note} fan={fan_note}"
    )

    try:
        while running:
            row = collector.read_once()
            row["device_id"] = device_id
            last_row = row
            pub = publisher.publish(row)
            pub_note = "skip" if not publisher.enabled else ("ok" if pub.ok else pub.message)
            mqtt = mqtt_publisher.publish(row)
            mqtt_status = (
                "skip"
                if not mqtt_publisher.enabled
                else (f"ok({mqtt.count})" if mqtt.ok else mqtt.message)
            )
            log.info(
                f"[{row['timestamp']}] device={device_id} "
                f"T={row['temperature_c']}C H={row['humidity_pct']}% "
                f"water_present={row.get('water_present')} "
                f"| spring={pub_note} mqtt={mqtt_status}"
            )
            if args.once:
                break
            end = time.time() + interval
            while running and time.time() < end:
                time.sleep(0.2)
    finally:
        if fan_listener is not None:
            fan_listener.close()
            fan_listener.fan.close()  # close() 가 off() 를 포함 — 종료 시 팬 정지 보장
        if capture_listener is not None:
            capture_listener.close()
        if water_listener is not None:
            water_listener.close()
            water_listener.pump.close()
        mqtt_publisher.close()
        collector.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
