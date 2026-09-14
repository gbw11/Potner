"""급수 펌프 CLI.

Pi 실행 예:
  python -m cli.water --config config/raspberry_pi.yaml prime            # 호스 채우기
  python -m cli.water --config config/raspberry_pi.yaml calibrate        # 유량/시동손실 보정
  python -m cli.water --config config/raspberry_pi.yaml calibrate --apply  # 보정값 config 에 기록
  python -m cli.water --config config/raspberry_pi.yaml dispense --ml 100
  python -m cli.water --config config/raspberry_pi.yaml run --sec 3

서버가 토양수분 기반으로 계산한 필요 급수량(ml)을 dispense 로 전달하면
보정값(flow_ml_per_sec, startup_sec)에 따라 가동 시간을 환산해 급수한다.
과급수 가드(config pump.safety)가 막으면 급수하지 않는다 — 실험용으로만 --force 로 우회.
"""

from __future__ import annotations

import argparse
import logging
import math
import sys
from dataclasses import dataclass
from pathlib import Path

if not __package__:  # `python cli/water.py` 직접 실행 시 프로젝트 루트를 경로에 추가
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.actuators import build_pump
from src.actuators.safety import build_guard
from src.config import load_config
from src.logging_setup import setup_logging

log = logging.getLogger(__name__)

# 보정 결과 sanity 범위 — 벗어나면 측정을 의심한다
MAX_SANE_STARTUP_SEC = 2.0
MIN_SANE_FLOW = 0.5
MAX_SANE_FLOW = 500.0


@dataclass(frozen=True)
class Calibration:
    flow_ml_per_sec: float
    startup_sec: float
    warnings: tuple[str, ...] = ()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="다이어프램 펌프 급수 제어")
    parser.add_argument("--config", default="config/default.yaml")
    sub = parser.add_subparsers(dest="command", required=True)

    dispense = sub.add_parser("dispense", help="지정한 양(ml)만큼 급수")
    dispense.add_argument("--ml", type=float, default=None, help="급수량 (기본: config pump.default_ml)")
    dispense.add_argument(
        "--force",
        action="store_true",
        help="과급수 가드(간격/누적/토양수분) 무시. 펌프 상한(max_run_sec)은 그대로 적용",
    )

    run = sub.add_parser("run", help="지정한 시간(초)만큼 가동 (테스트용)")
    run.add_argument("--sec", type=float, required=True)

    sub.add_parser("prime", help="호스에 물 채우기 (보정/첫 급수 전 필수)")

    calibrate = sub.add_parser(
        "calibrate",
        help="유량 보정: 짧게/길게 2회 가동해 flow_ml_per_sec 와 startup_sec 계산",
    )
    calibrate.add_argument("--short-sec", type=float, default=3.0)
    calibrate.add_argument("--long-sec", type=float, default=8.0)
    calibrate.add_argument(
        "--repeat",
        type=int,
        default=1,
        help="각 구간을 N회 측정해 평균 (기본 1). 2 이상이면 측정 오차가 줄어든다",
    )
    calibrate.add_argument(
        "--apply",
        action="store_true",
        help="계산된 보정값을 --config 파일의 pump 섹션에 바로 기록",
    )

    sub.add_parser("status", help="과급수 가드 상태(최근 24시간 누적량) 표시")
    sub.add_parser("stop", help="펌프 강제 정지 (비상용)")

    return parser.parse_args()


# --- 보정 계산 (순수 함수: 하드웨어·입력 없이 테스트 가능) ---


def fit_calibration(points: list[tuple[float, float]]) -> Calibration:
    """(가동시간, 실측 ml) 측정점들로 ml = flow * (t - startup) 직선 피팅.

    2점이면 두 점을 지나는 직선, 3점 이상이면 최소제곱. 측정이 물리적으로
    말이 안 되면(유량 0 이하 등) ValueError 로 거절한다 — 이상한 보정값이
    config 에 들어가면 이후 모든 급수량이 조용히 틀어지기 때문이다.
    """
    cleaned = [(float(t), float(ml)) for t, ml in points]
    for seconds, ml in cleaned:
        if not (math.isfinite(seconds) and math.isfinite(ml)):
            raise ValueError(f"측정값이 유한한 숫자가 아닙니다: ({seconds}, {ml})")
        if seconds <= 0:
            raise ValueError(f"가동 시간은 0보다 커야 합니다: {seconds}")
        if ml < 0:
            raise ValueError(f"측정량은 0 이상이어야 합니다: {ml}")
    if len(cleaned) < 2:
        raise ValueError("보정에는 서로 다른 가동 시간의 측정점이 2개 이상 필요합니다.")

    times = [t for t, _ in cleaned]
    if max(times) - min(times) < 1e-9:
        raise ValueError("모든 측정의 가동 시간이 같아 기울기를 구할 수 없습니다.")

    n = float(len(cleaned))
    mean_t = sum(t for t, _ in cleaned) / n
    mean_ml = sum(ml for _, ml in cleaned) / n
    var_t = sum((t - mean_t) ** 2 for t, _ in cleaned)
    cov = sum((t - mean_t) * (ml - mean_ml) for t, ml in cleaned)
    flow = cov / var_t

    if not math.isfinite(flow) or flow <= 0:
        raise ValueError(
            "가동 시간이 길수록 측정량이 늘어나지 않습니다 (유량 <= 0). 측정을 다시 하세요."
        )

    startup = mean_t - mean_ml / flow

    warnings: list[str] = []
    if startup < 0:
        if startup < -0.5:
            warnings.append(f"startup_sec={startup:.2f}: 측정 오차가 커 보입니다. 재보정을 권장합니다.")
        startup = 0.0  # 측정 오차로 약간 음수가 나올 수 있음
    elif startup > MAX_SANE_STARTUP_SEC:
        warnings.append(
            f"startup_sec={startup:.2f} 가 비정상적으로 큽니다. 호스 프라이밍 후 재보정하세요."
        )
    if flow < MIN_SANE_FLOW or flow > MAX_SANE_FLOW:
        warnings.append(
            f"flow_ml_per_sec={flow:.2f} 가 일반적인 다이어프램 펌프 범위를 벗어납니다. "
            "계량 단위(ml)와 측정 시간을 확인하세요."
        )

    return Calibration(
        flow_ml_per_sec=round(flow, 2),
        startup_sec=round(startup, 2),
        warnings=tuple(warnings),
    )


def update_pump_calibration(config_path: Path, calibration: Calibration) -> None:
    """config 파일의 pump 섹션에서 flow_ml_per_sec / startup_sec 줄만 값을 바꾼다.

    yaml 을 다시 덤프하면 주석과 순서가 통째로 날아가므로 해당 줄만 손댄다.
    키를 못 찾으면 조용히 넘어가지 않고 예외를 던진다 (반영된 줄 알고 넘어가면
    이후 급수가 옛 보정값으로 계속 나간다).
    """
    lines = config_path.read_text(encoding="utf-8").splitlines(keepends=True)
    targets = {
        "flow_ml_per_sec": f"{calibration.flow_ml_per_sec:.2f}",
        "startup_sec": f"{calibration.startup_sec:.2f}",
    }
    in_pump = False
    updated: set[str] = set()

    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if not line[0].isspace():  # 최상위 키 → 섹션 경계
            in_pump = stripped.startswith("pump:")
            continue
        if not in_pump:
            continue
        key = stripped.split(":", 1)[0].strip()
        if key in targets and key not in updated:
            indent = line[: len(line) - len(line.lstrip())]
            body = line.rstrip("\n")
            comment = ""
            if "#" in body:  # 값만 바꾸고 줄 끝 주석은 살린다
                text = body.split("#", 1)[1].strip()
                if text:
                    comment = f"  # {text}"
            newline = "\n" if line.endswith("\n") else ""
            lines[index] = f"{indent}{key}: {targets[key]}{comment}{newline}"
            updated.add(key)

    missing = sorted(set(targets) - updated)
    if missing:
        raise ValueError(
            f"{config_path} 의 pump 섹션에서 {', '.join(missing)} 키를 찾지 못했습니다. "
            "수동으로 반영하세요."
        )
    config_path.write_text("".join(lines), encoding="utf-8")


# --- 대화형 측정 ---


def _measure(pump, seconds: float, label: str) -> float:
    input(f"[{label}] 빈 계량컵을 노즐에 대고 Enter 를 누르면 {seconds:.1f}초 가동합니다...")
    pump.run_for(seconds)
    while True:
        raw = input(f"[{label}] 계량컵에 담긴 물의 양(ml)을 입력하세요: ").strip()
        try:
            value = float(raw)
            if value <= 0 or not math.isfinite(value):
                raise ValueError
            return value
        except ValueError:
            print("0보다 큰 숫자를 입력하세요.")


def cmd_calibrate(
    pump,
    short_sec: float,
    long_sec: float,
    *,
    repeat: int = 1,
    config_path: Path | None = None,
) -> int:
    if long_sec <= short_sec:
        print("--long-sec 은 --short-sec 보다 커야 합니다.", file=sys.stderr)
        return 1
    if long_sec > pump.max_run_sec:
        print(
            f"--long-sec({long_sec:.1f}s) 이 max_run_sec({pump.max_run_sec:.1f}s) 보다 큽니다. "
            "그대로 두면 상한까지만 돌아 보정값이 틀어집니다.",
            file=sys.stderr,
        )
        return 1
    repeat = max(1, int(repeat))

    print("=== 유량 보정 ===")
    print("주의: 호스가 비어 있으면 결과가 부정확합니다. 먼저 prime 으로 호스를 채우세요.")
    points: list[tuple[float, float]] = []
    for round_index in range(repeat):
        suffix = "" if repeat == 1 else f" (반복 {round_index + 1}/{repeat})"
        for seconds in (short_sec, long_sec):
            label = f"{seconds:.0f}s 가동{suffix}"
            points.append((seconds, _measure(pump, seconds, label)))

    try:
        calibration = fit_calibration(points)
    except ValueError as exc:
        print(f"보정 실패: {exc}", file=sys.stderr)
        return 1

    for warning in calibration.warnings:
        print(f"warn: {warning}")
        log.warning(warning)

    print()
    print("보정 결과:")
    print(f"  flow_ml_per_sec: {calibration.flow_ml_per_sec:.2f}")
    print(f"  startup_sec: {calibration.startup_sec:.2f}")
    print()

    if config_path is not None:
        try:
            update_pump_calibration(config_path, calibration)
        except (OSError, ValueError) as exc:
            print(f"config 반영 실패: {exc}", file=sys.stderr)
            return 1
        print(f"{config_path} 의 pump 섹션에 반영했습니다.")
    else:
        print("위 두 값을 config 의 pump 섹션에 반영하세요 (--apply 로 자동 반영 가능).")

    example_ml = 100.0
    expected = example_ml / calibration.flow_ml_per_sec + calibration.startup_sec
    print(
        f"검증: dispense --ml {example_ml:.0f} 실행 시 {expected:.1f}s 가동 → "
        f"실측이 {example_ml:.0f}ml 에 가까운지 확인"
    )
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


def cmd_dispense(pump, guard, ml: float, force: bool) -> int:
    if not math.isfinite(ml) or ml <= 0:
        print(f"급수량은 0보다 큰 숫자여야 합니다: {ml}", file=sys.stderr)
        return 1

    if guard is not None and not force:
        decision = guard.check(ml)
        if not decision.allowed:
            print(f"급수하지 않았습니다 ({decision.code}): {decision.reason}", file=sys.stderr)
            print("정말 급수하려면 --force 를 붙이세요.", file=sys.stderr)
            return 3
        if decision.limited:
            print(f"주의: {decision.reason}")
        ml = decision.ml
    elif guard is not None and force:
        print("warn: --force: 과급수 가드를 건너뜁니다.")

    plan = pump.plan_dispense(ml)
    print(f"급수 시작: {plan.requested_ml:.0f} ml (예상 {plan.run_sec:.1f}s)")
    result = pump.dispense_ml(ml)
    print(f"급수 완료: {result.dispensed_ml:.0f} ml / {result.duration_sec:.1f}s")
    if guard is not None:
        guard.record(result.dispensed_ml)
    if result.capped:
        print(
            f"warn: max_run_sec={pump.max_run_sec:.0f}s 상한에 걸려 "
            f"요청량 {result.requested_ml:.0f} ml 중 일부만 급수했습니다.",
            file=sys.stderr,
        )
    if result.floored:
        print(
            f"warn: min_run_sec={pump.min_run_sec:.2f}s 하한 때문에 요청량 "
            f"{result.requested_ml:.1f} ml 보다 많은 {result.dispensed_ml:.1f} ml 이 나갔습니다.",
            file=sys.stderr,
        )
    return 0


def cmd_status(pump, guard) -> int:
    print(f"유량 보정: {pump.flow_ml_per_sec:.2f} ml/s, 시동 손실 {pump.startup_sec:.2f}s")
    print(f"가동 시간 범위: {pump.min_run_sec:.2f}s ~ {pump.max_run_sec:.1f}s")
    if guard is None:
        print("과급수 가드: 꺼짐 (config pump.safety.enabled)")
        return 0
    limits = guard.limits
    print("과급수 가드: 켜짐")
    print(f"  1회 상한: {'없음' if limits.max_ml_per_dose is None else f'{limits.max_ml_per_dose:.0f} ml'}")
    print(f"  최소 간격: {limits.min_interval_sec:.0f}s")
    print(f"  24시간 상한: {'없음' if limits.daily_max_ml is None else f'{limits.daily_max_ml:.0f} ml'}")
    print(f"  토양수분 기준: {'없음' if limits.soil_wet_pct is None else f'{limits.soil_wet_pct:.0f}%'}")
    print(f"  최근 24시간 누적: {guard.used_ml():.0f} ml")
    return 0


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"Config not found: {config_path}", file=sys.stderr)
        return 1

    config = load_config(config_path)
    setup_logging(config)
    pump = build_pump(config)
    if pump is None:
        print("펌프가 비활성화되어 있거나 초기화에 실패했습니다 (config pump.enabled 확인).", file=sys.stderr)
        return 2
    guard = build_guard(config)

    try:
        if args.command == "dispense":
            ml = args.ml
            if ml is None:
                ml = float((config.get("pump") or {}).get("default_ml", 100.0))
            return cmd_dispense(pump, guard, float(ml), args.force)

        if args.command == "run":
            print(f"{args.sec:.1f}s 가동")
            pump.run_for(args.sec)
            print("정지")
            return 0

        if args.command == "prime":
            return cmd_prime(pump)

        if args.command == "calibrate":
            return cmd_calibrate(
                pump,
                args.short_sec,
                args.long_sec,
                repeat=args.repeat,
                config_path=config_path if args.apply else None,
            )

        if args.command == "status":
            return cmd_status(pump, guard)

        if args.command == "stop":
            pump.stop()
            print("펌프 정지")
            return 0

        return 1
    except ValueError as exc:
        print(f"오류: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        pump.stop()
        print("\n중단됨: 펌프 정지", file=sys.stderr)
        return 130
    finally:
        pump.close()


if __name__ == "__main__":
    sys.exit(main())
