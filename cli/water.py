"""급수 펌프 CLI.

Pi 실행 예:
  python -m cli.water --config config/raspberry_pi.yaml prime            # 호스 채우기
  python -m cli.water --config config/raspberry_pi.yaml calibrate        # 유량/시동손실 보정
  python -m cli.water --config config/raspberry_pi.yaml dispense --ml 100
  python -m cli.water --config config/raspberry_pi.yaml run --sec 3

서버가 토양수분 기반으로 계산한 필요 급수량(ml)을 dispense 로 전달하면
보정값(flow_ml_per_sec, startup_sec)에 따라 가동 시간을 환산해 급수한다.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from src.actuators import build_pump
from src.config import load_config


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="다이어프램 펌프 급수 제어")
    parser.add_argument("--config", default="config/default.yaml")
    sub = parser.add_subparsers(dest="command", required=True)

    dispense = sub.add_parser("dispense", help="지정한 양(ml)만큼 급수")
    dispense.add_argument("--ml", type=float, default=None, help="급수량 (기본: config pump.default_ml)")

    run = sub.add_parser("run", help="지정한 시간(초)만큼 가동 (테스트용)")
    run.add_argument("--sec", type=float, required=True)

    sub.add_parser("prime", help="호스에 물 채우기 (보정/첫 급수 전 필수)")

    calibrate = sub.add_parser(
        "calibrate",
        help="유량 보정: 짧게/길게 2회 가동해 flow_ml_per_sec 와 startup_sec 계산",
    )
    calibrate.add_argument("--short-sec", type=float, default=3.0)
    calibrate.add_argument("--long-sec", type=float, default=8.0)

    sub.add_parser("stop", help="펌프 강제 정지 (비상용)")

    return parser.parse_args()


def _measure(pump, seconds: float, label: str) -> float:
    input(f"[{label}] 빈 계량컵을 노즐에 대고 Enter 를 누르면 {seconds:.1f}초 가동합니다...")
    pump.run_for(seconds)
    while True:
        raw = input(f"[{label}] 계량컵에 담긴 물의 양(ml)을 입력하세요: ").strip()
        try:
            value = float(raw)
            if value <= 0:
                raise ValueError
            return value
        except ValueError:
            print("0보다 큰 숫자를 입력하세요.")


def cmd_calibrate(pump, short_sec: float, long_sec: float) -> int:
    if long_sec <= short_sec:
        print("--long-sec 은 --short-sec 보다 커야 합니다.", file=sys.stderr)
        return 1

    print("=== 유량 보정 ===")
    print("주의: 호스가 비어 있으면 결과가 부정확합니다. 먼저 prime 으로 호스를 채우세요.")
    ml_short = _measure(pump, short_sec, f"1/2 짧은 가동 {short_sec:.0f}s")
    ml_long = _measure(pump, long_sec, f"2/2 긴 가동 {long_sec:.0f}s")

    # 모델: ml = flow * (t - startup)  →  2점 직선 피팅
    flow = (ml_long - ml_short) / (long_sec - short_sec)
    if flow <= 0:
        print("긴 가동의 측정량이 더 적습니다. 측정을 다시 하세요.", file=sys.stderr)
        return 1
    startup = short_sec - ml_short / flow

    if startup < 0:
        # 측정 오차로 약간 음수가 나올 수 있음 → 0으로 처리
        if startup < -0.5:
            print(f"warn: startup_sec={startup:.2f} — 측정 오차가 커 보입니다. 재보정을 권장합니다.")
        startup = 0.0
    elif startup > 2.0:
        print(f"warn: startup_sec={startup:.2f} 가 비정상적으로 큽니다. 호스 프라이밍 후 재보정하세요.")

    print()
    print("보정 결과 — config 의 pump 섹션에 반영하세요:")
    print(f"  flow_ml_per_sec: {flow:.2f}")
    print(f"  startup_sec: {startup:.2f}")
    print()
    example_ml = 100.0
    print(f"검증: dispense --ml {example_ml:.0f} 실행 시 "
          f"{example_ml / flow + startup:.1f}s 가동 → 실측이 {example_ml:.0f}ml 에 가까운지 확인")
    return 0


def cmd_prime(pump) -> int:
    print("호스에 물을 채웁니다. 2초씩 가동하며 노즐 끝까지 물이 나올 때까지 반복합니다.")
    total = 0.0
    while total < 30.0:
        pump.run_for(2.0)
        total += 2.0
        ans = input("노즐에서 물이 나오나요? [y/N] ").strip().lower()
        if ans.startswith("y"):
            print("프라이밍 완료")
            return 0
    print("30초 넘게 물이 안 나옵니다. 배선/전원/호스 연결을 확인하세요.", file=sys.stderr)
    return 1


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"Config not found: {config_path}", file=sys.stderr)
        return 1

    config = load_config(config_path)
    pump = build_pump(config)
    if pump is None:
        print("펌프가 비활성화되어 있거나 초기화에 실패했습니다 (config pump.enabled 확인).", file=sys.stderr)
        return 2

    try:
        if args.command == "dispense":
            ml = args.ml
            if ml is None:
                ml = float(config.get("pump", {}).get("default_ml", 100.0))
            print(f"급수 시작: {ml:.0f} ml (예상 {pump.duration_for_ml(ml):.1f}s)")
            result = pump.dispense_ml(ml)
            print(f"급수 완료: {result.dispensed_ml:.0f} ml / {result.duration_sec:.1f}s")
            if result.capped:
                print(
                    f"warn: max_run_sec={pump.max_run_sec:.0f}s 상한에 걸려 "
                    f"요청량 {result.requested_ml:.0f} ml 중 일부만 급수했습니다.",
                    file=sys.stderr,
                )
            return 0

        if args.command == "run":
            print(f"{args.sec:.1f}s 가동")
            pump.run_for(args.sec)
            print("정지")
            return 0

        if args.command == "prime":
            return cmd_prime(pump)

        if args.command == "calibrate":
            return cmd_calibrate(pump, args.short_sec, args.long_sec)

        if args.command == "stop":
            pump.stop()
            print("펌프 정지")
            return 0

        return 1
    except KeyboardInterrupt:
        pump.stop()
        print("\n중단됨 — 펌프 정지", file=sys.stderr)
        return 130
    finally:
        pump.close()


if __name__ == "__main__":
    sys.exit(main())
