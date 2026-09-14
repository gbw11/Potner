"""스트림 C — 이미지 밝기 검사 테스트.

합성 PNG 를 테스트 안에서 직접 만들어 검사한다 (카메라·네트워크·Pillow 불필요).
로그 검증은 caplog 만 사용 — setup_logging 은 호출하지 않는다 (루트 핸들러 오염).
"""

from __future__ import annotations

import logging
import struct
import zlib

import pytest

from src.vision.quality import (
    VERDICT_OK,
    VERDICT_SKIPPED,
    VERDICT_TOO_BRIGHT,
    VERDICT_TOO_DARK,
    VERDICT_UNKNOWN,
    BrightnessRejected,
    BrightnessThresholds,
    DecodedImage,
    ImageDecodeError,
    analyze_image,
    analyze_image_bytes,
    analyze_luma,
    check_image,
    decode_png_luma,
    ensure_quality,
    should_deliver,
)


# --- 합성 PNG 생성기 ----------------------------------------------------


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def _png(
    width: int,
    height: int,
    rows: list[bytes],
    *,
    color_type: int = 2,
    depth: int = 8,
    filters: list[int] | None = None,
    palette: bytes = b"",
) -> bytes:
    filters = filters or [0] * height
    raw = b"".join(bytes([f]) + row for f, row in zip(filters, rows))
    ihdr = struct.pack(">IIBBBBB", width, height, depth, color_type, 0, 0, 0)
    out = b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr)
    if palette:
        out += _chunk(b"PLTE", palette)
    return out + _chunk(b"IDAT", zlib.compress(raw, 6)) + _chunk(b"IEND", b"")


def solid_rgb_png(value: int, width: int = 32, height: int = 24) -> bytes:
    """모든 픽셀이 (v, v, v) 인 RGB PNG — luma 도 v 가 된다."""
    row = bytes([value, value, value]) * width
    return _png(width, height, [row] * height, color_type=2)


def split_grey_png(dark_rows: int, light_rows: int, width: int = 20) -> bytes:
    """위쪽은 0(검정), 아래쪽은 255(흰색) 인 그레이스케일 PNG."""
    height = dark_rows + light_rows
    rows = [bytes([0]) * width] * dark_rows + [bytes([255]) * width] * light_rows
    return _png(width, height, rows, color_type=0)


def write_png(tmp_path, name: str, data: bytes) -> str:
    path = tmp_path / name
    path.write_bytes(data)
    return str(path)


@pytest.fixture
def thresholds() -> BrightnessThresholds:
    # 전수 검사로 고정 — 샘플링 때문에 판정이 흔들리지 않게
    return BrightnessThresholds(sample_step=1)


# --- 3분류 판정 (정상 / 저조도 / 과노출) --------------------------------


def test_normal_image_is_ok(tmp_path, thresholds):
    path = write_png(tmp_path, "normal.png", solid_rgb_png(128))

    report = analyze_image(path, thresholds=thresholds)

    assert report.verdict == VERDICT_OK
    assert report.label_ko == "정상"
    assert report.ok is True
    assert report.needs_recapture is False
    assert report.mean_brightness == pytest.approx(128, abs=1.0)
    assert (report.width, report.height) == (32, 24)


def test_dark_image_is_too_dark(tmp_path, thresholds):
    path = write_png(tmp_path, "dark.png", solid_rgb_png(10))

    report = analyze_image(path, thresholds=thresholds)

    assert report.verdict == VERDICT_TOO_DARK
    assert report.label_ko == "저조도"
    assert report.ok is False
    assert report.needs_recapture is True
    assert "평균 밝기" in report.reason


def test_bright_image_is_too_bright(tmp_path, thresholds):
    path = write_png(tmp_path, "bright.png", solid_rgb_png(250))

    report = analyze_image(path, thresholds=thresholds)

    assert report.verdict == VERDICT_TOO_BRIGHT
    assert report.label_ko == "과노출"
    assert report.ok is False
    assert report.needs_recapture is True


@pytest.mark.parametrize(
    "value,expected",
    [
        (0, VERDICT_TOO_DARK),
        (39, VERDICT_TOO_DARK),
        (40, VERDICT_OK),       # 하한은 포함 (미만일 때만 저조도)
        (200, VERDICT_OK),
        (215, VERDICT_OK),      # 상한도 포함
        (216, VERDICT_TOO_BRIGHT),
        (255, VERDICT_TOO_BRIGHT),
    ],
)
def test_threshold_boundaries(value, expected, thresholds):
    report = analyze_luma([value] * 100, thresholds=thresholds)

    assert report.verdict == expected


# --- 히스토그램(극단 픽셀 비율) 기반 판정 -------------------------------


def test_histogram_catches_crushed_blacks_even_when_mean_is_fine(thresholds):
    # 65% 완전 검정 + 35% 완전 흰색 → 평균 89(정상 범위)지만 검정 비율이 허용치 초과
    values = [0] * 65 + [255] * 35

    report = analyze_luma(values, thresholds=thresholds)

    assert report.mean_brightness == pytest.approx(89.25, abs=0.5)
    assert report.verdict == VERDICT_TOO_DARK
    assert report.dark_ratio == pytest.approx(0.65)
    assert "어두운 픽셀 비율" in report.reason


def test_histogram_catches_blown_highlights(thresholds):
    # 55% 흰색 날림 + 45% 중간톤 → 평균은 정상 범위, 흰색 비율이 허용치 초과
    values = [255] * 55 + [100] * 45

    report = analyze_luma(values, thresholds=thresholds)

    assert report.verdict == VERDICT_TOO_BRIGHT
    assert report.bright_ratio == pytest.approx(0.55)
    assert "밝은 픽셀 비율" in report.reason


def test_split_image_within_ratio_limits_passes(thresholds):
    # 검정 50% / 흰색 50% — 두 비율 모두 허용치 이내라 통과
    report = analyze_image_bytes(split_grey_png(10, 10), thresholds=thresholds)

    assert report.verdict == VERDICT_OK
    assert report.dark_ratio == pytest.approx(0.5)
    assert report.bright_ratio == pytest.approx(0.5)


# --- PNG 디코더 (표준 라이브러리) ---------------------------------------


def test_decodes_greyscale_png():
    decoded = decode_png_luma(_png(4, 2, [bytes([90]) * 4] * 2, color_type=0))

    assert (decoded.width, decoded.height) == (4, 2)
    assert list(decoded.luma) == [90] * 8


def test_decodes_rgba_png_ignoring_alpha():
    row = bytes([200, 200, 200, 0]) * 3   # 완전 투명이어도 luma 는 200
    decoded = decode_png_luma(_png(3, 1, [row], color_type=6))

    assert list(decoded.luma) == [200, 200, 200]


def test_decodes_palette_png():
    palette = bytes([0, 0, 0]) + bytes([255, 255, 255])
    rows = [bytes([0, 1, 1])]
    decoded = decode_png_luma(_png(3, 1, rows, color_type=3, palette=palette))

    assert list(decoded.luma) == [0, 255, 255]  # 인덱스 0=검정, 1=흰색


def test_decodes_16bit_png_using_high_byte():
    # depth 16 그레이스케일: 상위 바이트만 본다 (0x8000 → 128)
    row = b"\x80\x00" * 4
    decoded = decode_png_luma(_png(4, 1, [row], color_type=0, depth=16))

    assert list(decoded.luma) == [128] * 4


@pytest.mark.parametrize("filter_type", [0, 1, 2, 3, 4])
def test_all_png_filter_types_decode_to_same_pixels(filter_type):
    # 첫 행은 filter 0 으로 값을 심고, 둘째 행은 각 필터로 "위와 같음"을 표현
    width = 6
    first = bytes([120, 120, 120]) * width
    if filter_type == 0:
        second = first
    elif filter_type == 1:      # Sub: 첫 픽셀만 값, 이후 델타 0
        second = bytes([120, 120, 120]) + bytes(3 * (width - 1))
    elif filter_type == 2:      # Up: 위와 동일 → 델타 0
        second = bytes(3 * width)
    elif filter_type == 3:      # Average: (left + up) // 2 를 뺀 값
        second = bytearray()
        for i in range(3 * width):
            left = 120 if i >= 3 else 0
            second.append((120 - ((left + 120) >> 1)) & 0xFF)
        second = bytes(second)
    else:                       # Paeth: 예측자가 120 을 맞춤 → 델타 0
        second = bytes(3 * width)

    decoded = decode_png_luma(
        _png(width, 2, [first, second], color_type=2, filters=[0, filter_type])
    )

    assert list(decoded.luma) == [120] * (width * 2)


def test_sample_step_skips_pixels_but_keeps_verdict(tmp_path):
    path = write_png(tmp_path, "even.png", solid_rgb_png(128, width=40, height=40))

    full = analyze_image(path, thresholds=BrightnessThresholds(sample_step=1))
    sampled = analyze_image(path, thresholds=BrightnessThresholds(sample_step=8))

    assert full.sampled_pixels == 1600
    assert sampled.sampled_pixels == 200
    assert sampled.verdict == full.verdict == VERDICT_OK


# --- 디코드 실패 · 예외 처리 --------------------------------------------


def test_corrupt_png_raises_decode_error(tmp_path):
    path = write_png(tmp_path, "broken.png", b"\x89PNG\r\n\x1a\n" + b"garbage")

    with pytest.raises(ImageDecodeError):
        analyze_image(path)


def test_missing_file_raises_decode_error(tmp_path):
    with pytest.raises(ImageDecodeError):
        analyze_image(tmp_path / "nope.png")


def test_interlaced_png_is_rejected():
    ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 1)  # interlace=1
    data = (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(b"\x00" + b"\x00" * 6 + b"\x00" + b"\x00" * 6))
        + _chunk(b"IEND", b"")
    )

    with pytest.raises(ImageDecodeError, match="인터레이스"):
        decode_png_luma(data)


def test_jpeg_without_pillow_reports_missing_decoder(tmp_path, monkeypatch):
    monkeypatch.setattr("src.vision.quality.pillow_available", lambda: False)
    path = write_png(tmp_path, "photo.jpg", b"\xff\xd8\xff\xe0" + b"\x00" * 64)

    with pytest.raises(ImageDecodeError, match="Pillow"):
        analyze_image(path)


def test_jpeg_is_analyzed_when_pillow_is_available(tmp_path):
    # Pillow 가 없는 개발 PC 에서는 skip. Pi(python3-pil) / Pillow 설치 후 실기 경로 검증용.
    image_mod = pytest.importorskip("PIL.Image", reason="Pillow 미설치 (JPEG 경로는 실기 전용)")
    path = tmp_path / "photo.jpg"
    image_mod.new("RGB", (16, 16), (10, 10, 10)).save(path, format="JPEG")

    report = analyze_image(path, thresholds=BrightnessThresholds(sample_step=1))

    assert report.verdict == VERDICT_TOO_DARK
    assert (report.width, report.height) == (16, 16)


def test_check_image_never_raises_on_decode_failure(tmp_path):
    path = write_png(tmp_path, "broken.png", b"not an image at all")

    report = check_image(path)

    assert report.verdict == VERDICT_UNKNOWN
    assert report.label_ko == "분석 실패"
    assert report.ok is False
    assert report.mean_brightness is None
    assert should_deliver(report) is False   # 판정 불가는 후속 단계로 넘기지 않는다


def test_injected_decoder_is_used(tmp_path):
    path = write_png(tmp_path, "whatever.raw", b"\x00\x01\x02")

    def fake_decoder(data: bytes) -> DecodedImage:
        assert data == b"\x00\x01\x02"
        return DecodedImage(width=2, height=1, luma=[5, 5])

    report = analyze_image(path, thresholds=BrightnessThresholds(sample_step=1), decoder=fake_decoder)

    assert report.verdict == VERDICT_TOO_DARK
    assert report.width == 2


# --- 설정(camera.quality) 반영 ------------------------------------------


def test_thresholds_from_full_config():
    config = {
        "camera": {
            "quality": {
                "enabled": True,
                "min_mean_brightness": 60,
                "max_mean_brightness": 180,
                "sample_step": 2,
                "on_failure": "warn",
            }
        }
    }

    th = BrightnessThresholds.from_config(config)

    assert th.min_mean_brightness == 60.0
    assert th.max_mean_brightness == 180.0
    assert th.sample_step == 2
    assert th.reject_on_failure is False
    assert th.dark_pixel_level == BrightnessThresholds().dark_pixel_level  # 미지정은 기본값


def test_thresholds_from_missing_section_are_defaults():
    assert BrightnessThresholds.from_config({}) == BrightnessThresholds()
    assert BrightnessThresholds.from_config(None) == BrightnessThresholds()
    assert BrightnessThresholds.from_config({"camera": {}}) == BrightnessThresholds()


def test_project_yaml_quality_section_loads():
    from src.config import load_config

    for path in ("config/default.yaml", "config/raspberry_pi.yaml"):
        th = BrightnessThresholds.from_config(load_config(path))
        assert th.enabled is True
        # reject 면 안 된다: 탈락 시 result/capture 가 ERROR 로 나가고, 서버는 ERROR 를
        # 받으면 그날 자동 촬영 체인을 멈춘다 (사진이 아예 없는 것보다 밝은 사진이 낫다).
        assert th.reject_on_failure is False, f"{path}: on_failure 는 warn 이어야 한다"
        assert 0 < th.min_mean_brightness < th.max_mean_brightness <= 255
        assert th.sample_step >= 1


def test_disabled_check_skips_and_passes(tmp_path):
    path = write_png(tmp_path, "dark.png", solid_rgb_png(2))
    config = {"camera": {"quality": {"enabled": False}}}

    report = check_image(path, config)

    assert report.verdict == VERDICT_SKIPPED
    assert report.ok is True
    assert should_deliver(report) is True


# --- 후속 단계 게이팅 (통과한 이미지만 전달) ----------------------------


def test_only_passing_images_are_delivered(tmp_path):
    config = {"camera": {"quality": {"sample_step": 1}}}
    candidates = {
        "normal.png": solid_rgb_png(128),
        "dark.png": solid_rgb_png(5),
        "bright.png": solid_rgb_png(252),
    }
    paths = [write_png(tmp_path, name, data) for name, data in candidates.items()]

    delivered = [p for p in paths if should_deliver(check_image(p, config))]

    assert [p.rsplit("\\", 1)[-1].rsplit("/", 1)[-1] for p in delivered] == ["normal.png"]


def test_warn_mode_lets_failures_through(tmp_path):
    path = write_png(tmp_path, "dark.png", solid_rgb_png(5))
    config = {"camera": {"quality": {"on_failure": "warn", "sample_step": 1}}}

    report = check_image(path, config)

    assert report.verdict == VERDICT_TOO_DARK     # 판정은 그대로 실패
    assert report.ok is False
    assert should_deliver(report) is True         # 정책상 통과만 허용


def test_ensure_quality_raises_on_reject(tmp_path):
    path = write_png(tmp_path, "dark.png", solid_rgb_png(5))

    with pytest.raises(BrightnessRejected) as excinfo:
        ensure_quality(path, {"camera": {"quality": {"sample_step": 1}}})

    assert excinfo.value.report.verdict == VERDICT_TOO_DARK
    assert excinfo.value.report.needs_recapture is True


def test_ensure_quality_returns_report_when_ok(tmp_path):
    path = write_png(tmp_path, "normal.png", solid_rgb_png(128))

    report = ensure_quality(path, {"camera": {"quality": {"sample_step": 1}}})

    assert report.verdict == VERDICT_OK


def test_report_to_dict_is_json_friendly(tmp_path):
    import json

    path = write_png(tmp_path, "normal.png", solid_rgb_png(128))
    data = check_image(path).to_dict()

    assert data["verdict"] == VERDICT_OK
    assert data["ok"] is True
    assert data["needsRecapture"] is False
    json.dumps(data)  # 직렬화 가능해야 MQTT 결과/이벤트에 실을 수 있다


# --- 로그 (정상 INFO / 이상 WARNING / 실패 ERROR) ------------------------


def test_normal_logs_info(tmp_path, caplog):
    path = write_png(tmp_path, "normal.png", solid_rgb_png(128))

    with caplog.at_level(logging.DEBUG, logger="src.vision.quality"):
        check_image(path)

    records = [r for r in caplog.records if r.name == "src.vision.quality"]
    assert [r.levelno for r in records] == [logging.INFO]
    assert "밝기 정상" in records[0].message


@pytest.mark.parametrize("value,expected_ko", [(5, "저조도"), (252, "과노출")])
def test_abnormal_logs_warning(tmp_path, caplog, value, expected_ko):
    path = write_png(tmp_path, f"img{value}.png", solid_rgb_png(value))

    with caplog.at_level(logging.DEBUG, logger="src.vision.quality"):
        check_image(path, {"camera": {"quality": {"sample_step": 1}}})

    records = [r for r in caplog.records if r.name == "src.vision.quality"]
    assert [r.levelno for r in records] == [logging.WARNING]
    assert expected_ko in records[0].message
    assert "재촬영 필요=True" in records[0].message


def test_decode_failure_logs_error(tmp_path, caplog):
    path = write_png(tmp_path, "broken.png", b"nope")

    with caplog.at_level(logging.DEBUG, logger="src.vision.quality"):
        check_image(path)

    records = [r for r in caplog.records if r.name == "src.vision.quality"]
    assert [r.levelno for r in records] == [logging.ERROR]
    assert "밝기 분석 실패" in records[0].message
