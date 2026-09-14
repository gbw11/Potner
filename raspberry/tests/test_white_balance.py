"""색보정(화이트밸런스) — 컨트롤 조립 + gray-world 게인 계산.

실기 카메라 없이 돌아간다: picamera2 컨트롤 dict 는 순수 함수로 분리돼 있고,
색 분석은 stdlib PNG 디코더 경로를 쓴다.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

import pytest
import yaml

from src.vision.camera import AWB_MODES, build_camera_controls
from src.vision.whitebalance import (
    MAX_GAIN,
    analyze_rgb,
    sample_image_rgb,
    suggest_colour_gains,
)


# --- picamera2 컨트롤 ---------------------------------------------------


def test_default_controls_leave_awb_on_and_add_no_colour_gains():
    controls = build_camera_controls()
    assert controls["AwbEnable"] is True
    assert "ColourGains" not in controls
    assert "AwbMode" not in controls  # auto 는 모드를 명시하지 않는다


def test_awb_mode_maps_to_libcamera_enum():
    controls = build_camera_controls(awb_mode="indoor")
    assert controls["AwbEnable"] is True
    assert controls["AwbMode"] == AWB_MODES["indoor"]


def test_unknown_awb_mode_is_rejected():
    with pytest.raises(ValueError):
        build_camera_controls(awb_mode="greenish")


def test_colour_gains_disable_auto_awb():
    controls = build_camera_controls(colour_gains=[1.8, 1.4])
    assert controls["AwbEnable"] is False
    assert controls["ColourGains"] == (1.8, 1.4)


def test_colour_gains_wins_over_awb_mode():
    controls = build_camera_controls(awb_mode="daylight", colour_gains=(2.0, 1.5))
    assert controls["AwbEnable"] is False
    assert "AwbMode" not in controls


@pytest.mark.parametrize("bad", [[1.8], [1.8, 1.4, 1.0], [0, 1.4], [1.8, -1]])
def test_bad_colour_gains_are_rejected(bad):
    with pytest.raises(ValueError):
        build_camera_controls(colour_gains=bad)


def test_other_controls_survive_colour_gains():
    controls = build_camera_controls(colour_gains=[1.8, 1.4], saturation=1.2, contrast=1.3)
    assert controls["Saturation"] == 1.2
    assert controls["Contrast"] == 1.3
    assert controls["AeEnable"] is True


# --- gray-world 계산 ----------------------------------------------------


def test_neutral_image_needs_no_correction():
    report = analyze_rgb([(120, 120, 120)] * 10)
    assert report.cast == "none"
    red, blue = suggest_colour_gains(report, (1.5, 1.2))
    assert red == pytest.approx(1.5)
    assert blue == pytest.approx(1.2)


def test_green_cast_is_detected_and_raises_red_blue_gains():
    # G 가 R/B 보다 확실히 밝다 = 녹색빛
    report = analyze_rgb([(100, 150, 110)] * 10)
    assert report.cast == "green"
    assert report.ratio_r < 1.0 and report.ratio_b < 1.0

    red, blue = suggest_colour_gains(report, (1.0, 1.0))
    assert red > 1.0 and blue > 1.0
    # 더 많이 부족한 R 쪽이 더 크게 올라간다
    assert red > blue


def test_correction_makes_channels_match_after_applying_gains():
    report = analyze_rgb([(100, 150, 120)] * 4)
    red, blue = suggest_colour_gains(report, (1.0, 1.0))
    # 계산한 게인을 적용하면 세 채널 평균이 같아져야 한다 (gray-world 목표)
    assert report.mean_r * red == pytest.approx(report.mean_g, rel=0.01)
    assert report.mean_b * blue == pytest.approx(report.mean_g, rel=0.01)


def test_strength_scales_the_correction():
    report = analyze_rgb([(100, 150, 110)] * 4)
    full_r, _ = suggest_colour_gains(report, (1.0, 1.0), strength=1.0)
    half_r, _ = suggest_colour_gains(report, (1.0, 1.0), strength=0.5)
    none_r, _ = suggest_colour_gains(report, (1.0, 1.0), strength=0.0)
    assert none_r == pytest.approx(1.0)
    assert 1.0 < half_r < full_r


def test_gains_build_on_the_gains_actually_used():
    """자동 AWB 가 이미 게인을 걸고 찍은 사진이면 그 위에 곱해야 한다."""
    report = analyze_rgb([(100, 150, 120)] * 4)
    red_from_one, _ = suggest_colour_gains(report, (1.0, 1.0))
    red_from_two, _ = suggest_colour_gains(report, (2.0, 1.0))
    assert red_from_two == pytest.approx(red_from_one * 2, rel=0.01)


def test_gains_are_clamped_to_a_sane_range():
    # G 만 있고 R/B 가 거의 0 인 극단 이미지 → 게인이 무한대로 튀면 안 된다
    report = analyze_rgb([(1, 250, 1)] * 4)
    red, blue = suggest_colour_gains(report, (1.0, 1.0))
    assert red <= MAX_GAIN and blue <= MAX_GAIN


def test_suggested_gains_are_yaml_friendly_numbers():
    report = analyze_rgb([(103, 151, 117)] * 4)
    gains = suggest_colour_gains(report, (1.0, 1.0))
    assert all(isinstance(g, float) and round(g, 3) == g for g in gains)


# --- 이미지에서 직접 --------------------------------------------------


def _rgb_png(pixel: tuple[int, int, int], width: int = 8, height: int = 8) -> bytes:
    raw = b"".join(b"\x00" + bytes(pixel) * width for _ in range(height))

    def chunk(tag: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body)) + tag + body
            + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def test_green_png_reports_green_cast_without_pillow():
    report = sample_image_rgb(_rgb_png((90, 140, 100)), step=1)
    assert report.cast == "green"
    assert report.width == 8 and report.height == 8
    assert report.mean_g > report.mean_r


def test_sample_step_reduces_pixel_count():
    dense = sample_image_rgb(_rgb_png((120, 120, 120), 16, 16), step=1)
    sparse = sample_image_rgb(_rgb_png((120, 120, 120), 16, 16), step=8)
    assert dense.sampled_pixels == 256
    assert sparse.sampled_pixels == 4
    assert dense.mean_g == sparse.mean_g


# --- config 배선 --------------------------------------------------------


@pytest.mark.parametrize("name", ["config/default.yaml", "config/raspberry_pi.yaml"])
def test_project_yaml_camera_awb_section_is_valid(name):
    cfg = yaml.safe_load(Path(name).read_text(encoding="utf-8"))
    cam = cfg["camera"]
    # awb_mode 는 실제로 존재하는 모드여야 하고, 그대로 컨트롤로 조립돼야 한다
    controls = build_camera_controls(
        exposure_value=float(cam.get("exposure_value", 1.0)),
        brightness=float(cam.get("brightness", 0.1)),
        contrast=float(cam.get("contrast", 1.1)),
        saturation=float(cam.get("saturation", 1.05)),
        awb_mode=str(cam.get("awb_mode", "auto")),
        colour_gains=cam.get("colour_gains"),
    )
    assert "AwbEnable" in controls
