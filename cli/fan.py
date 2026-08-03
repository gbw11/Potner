"""송풍 팬 CLI.

Pi 실행 예:
  python -m cli.fan --config config/raspberry_pi.yaml run --sec 10 --speed 100
  python -m cli.fan --config config/raspberry_pi.yaml on --speed 60   # Ctrl+C 로 정지
  python -m cli.fan --config config/raspberry_pi.yaml off

소프트웨어 PWM 특성상 프로세스가 살아 있는 동안만 팬이 돈다.
(on 은 Ctrl+C 를 누를 때까지 켜 두는 방식)
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

if not __package__:  # `python cli/fan.py` 직접 실행 시 프로젝트 루트를 경로에 추가
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.actuators import build_fan
from src.config import load_config


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="시로코 팬 (MOSFET PWM) 제어")
    parser.add_argument("--config", default="config/default.yaml")
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="지정 시간(초) 동안 가동 후 정지")
    run.add_argument("--sec", type=float, required=True)
    run.add_argument("--speed", type=float, default=None, help="풍량 % (기본: config fan.default_speed_pct)")

    on = sub.add_parser("on", help="Ctrl+C 를 누를 때까지 가동")
    on.add_argument("--speed", type=float, default=None)

    sub.add_parser("off", help="팬 정지 및 핀 풀다운 주차 (비상용)")

    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"Config not found: {config_path}", file=sys.stderr)
        return 1

    config = load_config(config_path)
    fan = build_fan(config)
    if fan is None:
        print("팬이 비활성화되어 있거나 초기화에 실패했습니다 (config fan.enabled 확인).", file=sys.stderr)
        return 2

    default_speed = float(config.get("fan", {}).get("default_speed_pct", 100.0))

    try:
        if args.command == "run":
            speed = default_speed if args.speed is None else args.speed
            print(f"팬 가동: {min(max(speed, 0.0), 100.0):.0f}% / {args.sec:.1f}s")
            fan.run_for(args.sec, speed)
            print("정지")
            return 0

        if args.command == "on":
            speed = default_speed if args.speed is None else args.speed
            applied = fan.on(speed)
            print(f"팬 가동: {applied:.0f}% — Ctrl+C 로 정지")
            while True:
                time.sleep(1)

        if args.command == "off":
            fan.off()
            print("팬 정지")
            return 0

        return 1
    except KeyboardInterrupt:
        print("\n중단됨 — 팬 정지", file=sys.stderr)
        return 130
    finally:
        fan.close()


if __name__ == "__main__":
    sys.exit(main())
