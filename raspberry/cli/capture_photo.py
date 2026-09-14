#!/usr/bin/env python3
"""Capture one photo and save under data/camera/.

- On Raspberry Pi: shoot with OV5647 → data/camera/
- On Windows PC: shoot on Pi over SSH, then sync into local data/camera/

Usage (from project root):
  python -m cli.capture_photo
  python -m cli.capture_photo --mock          # PC dummy PNG only
  python -m cli.capture_photo --local-only    # do not SSH (Pi or mock)

색보정 (녹색빛 등 색 캐스트 잡기):
  python -m cli.capture_photo --analyze-color              # 찍고 R/G/B 편차 + 권장 게인 출력
  python -m cli.capture_photo --wb-gains 1.8,1.4 --analyze-color   # 게인 시험 촬영
  python -m cli.capture_photo --analyze-file data/camera/frame_x.jpg   # 기존 사진만 분석
권장 게인이 마음에 들면 config 의 camera.colour_gains 에 [red, blue] 로 적어두면 고정된다.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

if not __package__:  # `python cli/capture_photo.py` 직접 실행 시 프로젝트 루트를 경로에 추가
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.config import load_config
from src.vision import build_camera, build_camera_store

REMOTE_HOST = "raspberrypi"
REMOTE_DIR = "~/S15P11E104"
LOCAL_CAMERA_DIR = Path("data/camera")


def _is_raspberry_pi() -> bool:
    return Path("/proc/device-tree/model").exists() or Path("/proc/device-tree/system").exists()


def _parse_gains(text: str) -> tuple[float, float]:
    parts = [p for p in text.replace(",", " ").split() if p]
    if len(parts) != 2:
        raise ValueError(f"--wb-gains 는 'red,blue' 형식이어야 합니다: {text!r}")
    return float(parts[0]), float(parts[1])


def _print_color_report(image_path: str, applied_gains: tuple[float, float] | None, strength: float) -> None:
    """촬영 결과의 색 편차와 권장 ColourGains 출력."""
    from src.vision.quality import ImageDecodeError
    from src.vision.whitebalance import sample_image_file, suggest_colour_gains

    try:
        report = sample_image_file(image_path)
    except ImageDecodeError as exc:
        print(f"\n[색 분석 실패] {exc}", file=sys.stderr)
        return
    except OSError as exc:
        print(f"\n[색 분석 실패] 파일을 읽을 수 없습니다: {exc}", file=sys.stderr)
        return

    cast_ko = {
        "none": "치우침 없음",
        "green": "녹색빛",
        "magenta": "마젠타빛",
        "red": "붉은빛",
        "cyan": "청록빛",
        "blue": "푸른빛",
        "yellow": "노란빛",
    }.get(report.cast, report.cast)

    print("\n--- 색 분석 (gray-world) ---")
    print(f"평균 RGB      : R {report.mean_r:.1f} / G {report.mean_g:.1f} / B {report.mean_b:.1f}")
    print(f"G 대비 비율   : R {report.ratio_r:.3f} / B {report.ratio_b:.3f}  (1.000 이면 캐스트 없음)")
    print(f"판정          : {cast_ko}  (샘플 {report.sampled_pixels} px)")

    if applied_gains is None:
        # ColourGains 는 raw 채널에 곱하는 "절대" 게인이다. 그 사진을 찍을 때 AWB 가
        # 뭘 걸었는지 모르면 절대값을 제안할 수 없다 (1.0 기준으로 계산하면 완전히 틀린다).
        factor_r = 1.0 + strength * (1.0 / report.ratio_r - 1.0) if report.ratio_r else 1.0
        factor_b = 1.0 + strength * (1.0 / report.ratio_b - 1.0) if report.ratio_b else 1.0
        print("촬영 시 게인  : 알 수 없음 (이 파일에는 촬영 메타데이터가 없음)")
        print(f"필요 보정 배율: red ×{factor_r:.3f} / blue ×{factor_b:.3f}   (strength={strength})")
        print("  → 절대 colour_gains 값은 촬영 시 AWB 게인을 알아야 계산됩니다.")
        print("  → `python -m cli.capture_photo --analyze-color` 로 직접 찍으면 그 값까지 나옵니다.")
        return

    red, blue = suggest_colour_gains(report, applied_gains, strength=strength)
    print(f"촬영 시 게인  : red {applied_gains[0]:.3f} / blue {applied_gains[1]:.3f}")
    print(f"권장 게인     : colour_gains: [{red}, {blue}]   (strength={strength})")
    print("  → config/raspberry_pi.yaml 의 camera: 아래에 넣고 다시 찍어 확인하세요.")


def _applied_gains(camera: object, wb_gains: tuple[float, float] | None) -> tuple[float, float] | None:
    """이번 촬영에 실제로 적용된 (red, blue) 게인. 자동 AWB 면 카메라 metadata 에서 읽는다."""
    if wb_gains is not None:
        return wb_gains
    meta = getattr(camera, "last_metadata", None) or {}
    gains = meta.get("ColourGains")
    if isinstance(gains, (list, tuple)) and len(gains) == 2:
        return float(gains[0]), float(gains[1])
    return None


def _run_local_capture(
    config_path: Path,
    *,
    force_mock: bool = False,
    wb_gains: tuple[float, float] | None = None,
    awb_mode: str | None = None,
    analyze_color: bool = False,
    strength: float = 1.0,
) -> int:
    config = load_config(config_path)
    cam_cfg = dict(config.get("camera") or {})
    cam_cfg["enabled"] = True
    cam_cfg["path"] = str(LOCAL_CAMERA_DIR)
    if wb_gains is not None:
        cam_cfg["colour_gains"] = list(wb_gains)
    if awb_mode is not None:
        cam_cfg["awb_mode"] = awb_mode
        if wb_gains is None:
            # AWB 모드를 시험하려는 것이므로 고정 게인은 잠시 치운다
            cam_cfg.pop("colour_gains", None)

    if force_mock or str(config.get("platform", "")).lower() == "mock":
        cam_cfg["driver"] = "mock"
        config["platform"] = "mock"
    else:
        cam_cfg.setdefault("driver", "picamera2")

    config["camera"] = cam_cfg
    camera = build_camera(config)
    if camera is None:
        print("Camera could not be created.", file=sys.stderr)
        return 1

    store = build_camera_store(config)
    try:
        result = store.capture(camera)
        applied = _applied_gains(camera, wb_gains)
    finally:
        camera.close()

    print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
    if result.ok:
        print(f"\nSaved: {result.path}")
        if analyze_color:
            _print_color_report(result.path, applied, strength)
        return 0
    print(f"\nCapture failed: {result.error}", file=sys.stderr)
    return 1


def _sync_from_pi() -> None:
    LOCAL_CAMERA_DIR.mkdir(parents=True, exist_ok=True)
    remote = f"{REMOTE_HOST}:{REMOTE_DIR}/data/camera/."
    cmd = ["scp", "-r", remote, str(LOCAL_CAMERA_DIR)]
    subprocess.run(cmd, check=True)


def _capture_on_pi_and_sync(
    config_name: str = "config/raspberry_pi.yaml",
    *,
    extra_args: str = "",
) -> int:
    print(f"[PC] Capture on {REMOTE_HOST}, then save to {LOCAL_CAMERA_DIR}/ ...")
    remote_cmd = (
        f"cd {REMOTE_DIR} && . .venv/bin/activate && "
        f"python -m cli.capture_photo --local-only --config {config_name}{extra_args}"
    )
    result = subprocess.run(["ssh", REMOTE_HOST, remote_cmd])
    if result.returncode != 0:
        print("Remote capture failed.", file=sys.stderr)
        return result.returncode or 1

    _sync_from_pi()
    print(f"\nSynced to {LOCAL_CAMERA_DIR.resolve()}")
    latest = sorted(LOCAL_CAMERA_DIR.glob("frame_*.jpg"), key=lambda p: p.stat().st_mtime)
    if latest:
        print(f"Latest: {latest[-1]}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Capture photo → data/camera/")
    parser.add_argument("--config", default=None, help="Config YAML path")
    parser.add_argument("--mock", action="store_true", help="Force mock image (no Pi)")
    parser.add_argument(
        "--local-only",
        action="store_true",
        help="Capture on this machine only (used on Pi; no SSH sync)",
    )
    parser.add_argument(
        "--analyze-color",
        action="store_true",
        help="촬영 후 R/G/B 편차와 권장 colour_gains 출력",
    )
    parser.add_argument(
        "--analyze-file",
        default=None,
        help="촬영하지 않고 기존 이미지 파일만 색 분석",
    )
    parser.add_argument(
        "--wb-gains",
        default=None,
        help="이번 촬영에만 수동 화이트밸런스 게인 적용 (예: 1.8,1.4)",
    )
    parser.add_argument(
        "--awb",
        default=None,
        help="AWB 모드 시험 (auto/incandescent/tungsten/fluorescent/indoor/daylight/cloudy)",
    )
    parser.add_argument(
        "--strength",
        type=float,
        default=1.0,
        help="권장 게인의 보정 강도 0.0~1.0 (기본 1.0 = 완전 gray-world)",
    )
    args = parser.parse_args()

    if args.analyze_file:
        _print_color_report(args.analyze_file, None, args.strength)
        return 0

    try:
        wb_gains = _parse_gains(args.wb_gains) if args.wb_gains else None
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    if args.config:
        config_path = Path(args.config)
    elif _is_raspberry_pi() or args.local_only:
        config_path = Path("config/raspberry_pi.yaml")
        if not config_path.exists():
            config_path = Path("config/default.yaml")
    else:
        config_path = Path("config/default.yaml")

    if not config_path.exists():
        print(f"Config not found: {config_path}", file=sys.stderr)
        return 1

    # Pi (or explicit local): save directly under data/camera
    if args.local_only or _is_raspberry_pi() or args.mock:
        return _run_local_capture(
            config_path,
            force_mock=args.mock,
            wb_gains=wb_gains,
            awb_mode=args.awb,
            analyze_color=args.analyze_color,
            strength=args.strength,
        )

    # Windows / other PC: shoot on Pi and sync into local data/camera
    extra = ""
    if args.analyze_color:
        extra += " --analyze-color"
    if args.wb_gains:
        extra += f" --wb-gains {args.wb_gains}"
    if args.awb:
        extra += f" --awb {args.awb}"
    if args.strength != 1.0:
        extra += f" --strength {args.strength}"
    return _capture_on_pi_and_sync("config/raspberry_pi.yaml", extra_args=extra)


if __name__ == "__main__":
    raise SystemExit(main())
