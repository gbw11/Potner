"""수위 센서(플로트 스위치) 동작 확인 CLI.

collector·MQTT·다른 센서를 전혀 import 하지 않는 독립 도구 —
sensor-collector 서비스가 떠 있어도 그대로 같이 실행할 수 있다
(GPIO27 만 사용, 펌프 17/18·팬 23·DHT 4·I2C 와 겹치지 않음).

Pi 실행 예 (프로젝트 루트에서):
  python -m cli.water_level watch              # 상태 변화를 실시간 출력, Ctrl+C 종료
  python -m cli.water_level read               # 1회 읽고 JSON 출력
  python -m cli.water_level watch --no-invert  # 센서 재장착 후 극성이 원래대로면
  python -m cli.water_level read --pin 22      # 다른 핀(예: 물받이 2호기)

PC 흐름 확인:
  python -m cli.water_level read --mock
  python -m cli.water_level watch --mock       # toggle mock은 read 마다 교대하며 매번 출력된다
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path

if not __package__:  # `python cli/water_level.py` 직접 실행 시 프로젝트 루트를 경로에 추가
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.sensors.water_level import (
    DEFAULT_INVERT,
    DEFAULT_PIN,
    FloatSwitchSensor,
    MockFloatSwitchSensor,
    WaterLevelReading,
)


def _describe(reading: WaterLevelReading) -> str:
    raw = "접점 닫힘(LOW)" if reading.raw_closed else "접점 열림(HIGH)"
    interp = "물 있음" if reading.water_present else "물 없음"
    return f"{raw} → {interp}"


def _build_sensor(args: argparse.Namespace):
    if args.mock:
        return MockFloatSwitchSensor(invert=args.invert, toggle=True)
    return FloatSwitchSensor(args.pin, invert=args.invert)


def cmd_read(args: argparse.Namespace) -> int:
    sensor = _build_sensor(args)
    try:
        # 다수결 안정화 읽기 — 뜨개 출렁임 순간값이 1회 판정을 흔들지 않게 한다
        reading = sensor.read_stable()
    finally:
        sensor.close()
    print(
        json.dumps(
            {
                "pin": sensor.pin,
                "timestamp": datetime.now().astimezone().isoformat(timespec="seconds"),
                **reading.to_dict(),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


def cmd_watch(args: argparse.Namespace) -> int:
    sensor = _build_sensor(args)
    pin_label = "mock" if args.mock else f"GPIO{args.pin} (물리 13번={DEFAULT_PIN} 기준)"
    # em-dash 등 cp949 밖 문자는 금지 — Windows 콘솔이 파이프 모드일 때 출력이 죽는다
    print(f"[water-level] {pin_label} 감시 시작 (Ctrl+C 로 종료)")
    print("접점이 바뀌면 그 즉시 아래에 출력됩니다 (ON/OFF 리드스위치라 대기/확정 없음).")
    print("'물 있음/없음' 해석이 실물과 반대면 --no-invert 로 다시 확인하세요.\n")

    try:
        last = sensor.read()
        print(f"{datetime.now():%H:%M:%S} 현재: {_describe(last)}")
        while True:
            time.sleep(args.interval)
            reading = sensor.read()
            if reading.raw_closed == last.raw_closed:
                continue
            print(f"{datetime.now():%H:%M:%S} 변화: {_describe(reading)}")
            last = reading
    except KeyboardInterrupt:
        print("\n[water-level] 종료")
        return 0
    finally:
        sensor.close()


def main(argv: list[str] | None = None) -> int:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--pin", type=int, default=DEFAULT_PIN, help=f"BCM 핀 번호 (기본 {DEFAULT_PIN} = 물리 13번)")
    common.add_argument(
        "--invert",
        action=argparse.BooleanOptionalAction,
        default=DEFAULT_INVERT,
        help="닫힘=물 없음 해석 (기본: 실물 확인값 켜짐). 재장착 후 반대면 --no-invert",
    )
    common.add_argument("--mock", action="store_true", help="하드웨어 없이 흐름만 확인 (PC)")

    parser = argparse.ArgumentParser(description="수위 센서(플로트 스위치) 동작 확인")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("read", parents=[common], help="1회 읽고 JSON 출력")
    watch = sub.add_parser("watch", parents=[common], help="상태 변화를 실시간 출력 (Ctrl+C 종료)")
    watch.add_argument("--interval", type=float, default=0.1, help="폴링 간격 초 (기본 0.1)")

    args = parser.parse_args(argv)
    try:
        if args.command == "read":
            return cmd_read(args)
        return cmd_watch(args)
    except ImportError as exc:
        # PC 에서 --mock 없이 실행한 경우가 대부분 — traceback 대신 안내만 남긴다
        print(f"오류: {exc}", file=sys.stderr)
        print("하드웨어 없이 흐름만 확인하려면 --mock 을 붙이세요.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
