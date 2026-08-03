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
  python -m cli.water_level watch --mock       # toggle mock은 read 마다 교대 → 감지/취소만
                                               # 반복되고 확정이 없는 것이 정상 (필터 동작 증거)
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Optional

if not __package__:  # `python cli/water_level.py` 직접 실행 시 프로젝트 루트를 경로에 추가
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.sensors.water_level import (
    DEFAULT_INVERT,
    DEFAULT_PIN,
    FloatSwitchSensor,
    MockFloatSwitchSensor,
    WaterLevelReading,
)


@dataclass(frozen=True)
class HoldEvent:
    """HoldFilter 가 보고하는 판정 이벤트.

    kind:
      detected  — 접점이 확정 상태와 달라진 순간 (아직 미확정)
      confirmed — 새 상태가 hold_sec 연속 유지됨 → 실제 수위 변화로 인정
      cancelled — hold_sec 을 못 채우고 원래 상태로 복귀 → 출렁임으로 무시
    elapsed_sec: 후보 상태가 유지된 시간 (detected 는 0.0)
    """

    kind: str
    elapsed_sec: float


class HoldFilter:
    """watch 용 유지시간 필터 — 접촉 인정 기준을 명시적으로 만든다.

    기준: 접점의 새 상태가 hold_sec 동안 **연속** 유지되면 확정(confirmed),
    그 전에 원래 상태로 돌아오면 취소(cancelled). 모든 단계가 이벤트로
    보고되므로 "왜 안 뜨지"가 생기지 않는다. 시계는 now 인자(단조 시각)로
    주입받아 실시간 sleep 없이 테스트할 수 있다. hold_sec<=0 이면 즉시 확정.
    """

    def __init__(self, hold_sec: float, initial: bool) -> None:
        self._hold_sec = hold_sec
        self._state = initial  # 마지막으로 확정된 상태
        self._candidate_since: Optional[float] = None  # 반대 상태 최초 관측 시각

    @property
    def state(self) -> bool:
        """마지막으로 확정된 상태."""
        return self._state

    def update(self, raw_closed: bool, now: float) -> Optional[HoldEvent]:
        """관측값 1건을 반영하고, 판정 단계가 바뀐 순간에만 이벤트를 돌려준다."""
        if raw_closed == self._state:
            if self._candidate_since is not None:  # 유지 중 복귀 → 후보 폐기
                elapsed = now - self._candidate_since
                self._candidate_since = None
                return HoldEvent("cancelled", elapsed)
            return None
        if self._candidate_since is None:
            if self._hold_sec <= 0:  # 필터 끔 — 즉시 확정
                self._state = raw_closed
                return HoldEvent("confirmed", 0.0)
            self._candidate_since = now
            return HoldEvent("detected", 0.0)
        elapsed = now - self._candidate_since
        if elapsed >= self._hold_sec:
            self._state = raw_closed
            self._candidate_since = None
            return HoldEvent("confirmed", elapsed)
        return None


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
    print("접촉 인정 기준:")
    print("  감지 = 접점이 바뀐 순간 (아직 미확정)")
    print(f"  확정 = 새 상태가 {args.hold:g}초 연속 유지됨 (실제 수위 변화로 인정)")
    print(f"  취소 = {args.hold:g}초를 못 채우고 복귀 (출렁임으로 무시)")
    print("'물 있음/없음' 해석이 실물과 반대면 --no-invert 로 다시 확인하세요.\n")

    try:
        first = sensor.read()
        print(f"{datetime.now():%H:%M:%S} 현재: {_describe(first)}")
        hold = HoldFilter(args.hold, initial=first.raw_closed)
        while True:
            time.sleep(args.interval)
            reading = sensor.read()
            event = hold.update(reading.raw_closed, time.monotonic())
            if event is None:
                continue
            stamp = f"{datetime.now():%H:%M:%S}"
            if event.kind == "detected":
                print(f"{stamp} 감지: {_describe(reading)} ({args.hold:g}초 유지되면 확정)")
            elif event.kind == "confirmed":
                print(f"{stamp} 확정: {_describe(reading)} ({event.elapsed_sec:.1f}초 유지)")
            else:  # cancelled
                print(f"{stamp} 취소: {event.elapsed_sec:.1f}초 만에 복귀 (출렁임으로 무시)")
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
    watch.add_argument(
        "--hold",
        type=float,
        default=1.0,
        help="새 상태가 이 시간(초) 동안 연속 유지되면 확정 (기본 1.0, 0 이면 즉시 확정)",
    )

    args = parser.parse_args(argv)
    if args.command == "read":
        return cmd_read(args)
    return cmd_watch(args)


if __name__ == "__main__":
    raise SystemExit(main())
