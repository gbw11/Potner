"""화이트밸런스(색 캐스트) 분석 및 보정 게인 계산.

밝기 검사(`quality.py`)가 "얼마나 밝은가"를 본다면, 여기는 "어느 색으로 치우쳤는가"를 본다.
용도는 두 가지:

1. 촬영된 이미지의 R/G/B 평균을 재서 색 캐스트(예: 녹색빛)를 수치로 보여준다.
2. 그 편차를 없애는 picamera2 `ColourGains` 값을 계산해 `config.camera.colour_gains` 에
   그대로 넣을 수 있게 한다.

계산은 gray-world 가정이다 — 장면 전체의 평균색은 회색이어야 한다는 것. 화면 대부분이 잎인
식물 사진에서는 이 가정이 과하게 작동해 잎까지 탈색시킬 수 있으므로, `strength` 로 보정
강도를 낮출 수 있게 두었다 (0.0 = 보정 없음, 1.0 = 완전 gray-world).
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Optional, Sequence

from .quality import PNG_SIGNATURE, ImageDecodeError, _unfilter

# 게인 하한/상한 — libcamera 가 받아주는 현실적 범위. 밖으로 나가면 촬영이 통째로 실패한다.
MIN_GAIN = 0.1
MAX_GAIN = 8.0


@dataclass(frozen=True)
class ColorReport:
    """이미지 한 장의 채널 평균과 그로부터 계산한 보정 제안."""

    mean_r: float
    mean_g: float
    mean_b: float
    sampled_pixels: int
    #: 녹색을 1.0 으로 뒀을 때 R/B 가 얼마나 부족/과한지. 1.0 이면 캐스트 없음.
    ratio_r: float
    ratio_b: float
    cast: str            # "none" | "green" | "magenta" | "blue" | "red" ...
    width: Optional[int] = None
    height: Optional[int] = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def _classify_cast(mean_r: float, mean_g: float, mean_b: float, tolerance: float = 0.02) -> str:
    """평균색이 어느 쪽으로 치우쳤는지 한 단어로."""
    total = mean_r + mean_g + mean_b
    if total <= 0:
        return "unknown"
    # 무채색 기준 1/3 에서 2%p 만 벗어나도 눈에는 색이 낀 것으로 보인다 —
    # 실측(2026-08-07 Pi 사진)에서 G 비중 0.367(=+3.4%p)이 "녹색빛"으로 보고된 수준이었다.
    # 무채색이라면 세 채널이 1/3 씩
    dr = mean_r / total - 1 / 3
    dg = mean_g / total - 1 / 3
    db = mean_b / total - 1 / 3
    biggest = max((abs(dr), "r"), (abs(dg), "g"), (abs(db), "b"))
    if biggest[0] <= tolerance:
        return "none"
    if biggest[1] == "g":
        return "green" if dg > 0 else "magenta"
    if biggest[1] == "r":
        return "red" if dr > 0 else "cyan"
    return "blue" if db > 0 else "yellow"


def analyze_rgb(pixels: Sequence[tuple[int, int, int]]) -> ColorReport:
    """RGB 픽셀 시퀀스 → 채널 평균 리포트."""
    count = len(pixels)
    if count == 0:
        raise ImageDecodeError("분석할 픽셀이 없습니다")
    sum_r = sum_g = sum_b = 0
    for r, g, b in pixels:
        sum_r += r
        sum_g += g
        sum_b += b
    mean_r = sum_r / count
    mean_g = sum_g / count
    mean_b = sum_b / count
    return ColorReport(
        mean_r=mean_r,
        mean_g=mean_g,
        mean_b=mean_b,
        sampled_pixels=count,
        ratio_r=(mean_r / mean_g) if mean_g else 0.0,
        ratio_b=(mean_b / mean_g) if mean_g else 0.0,
        cast=_classify_cast(mean_r, mean_g, mean_b),
    )


def suggest_colour_gains(
    report: ColorReport,
    current: tuple[float, float] = (1.0, 1.0),
    *,
    strength: float = 1.0,
) -> tuple[float, float]:
    """현재 게인 + 측정 결과 → 새 (red_gain, blue_gain).

    녹색이 낀 사진은 R/B 가 G 보다 어둡게 찍힌 것이므로 R·B 게인을 올려서 맞춘다.
    `current` 는 그 사진을 찍을 때 실제로 적용됐던 게인 (picamera2 metadata 의 ColourGains).
    자동 AWB 였다면 카메라가 고른 값이 들어오고, 모르면 (1.0, 1.0) 로 두면 된다.
    """
    strength = max(0.0, min(1.0, float(strength)))
    cur_r, cur_b = float(current[0]), float(current[1])

    def _corrected(cur: float, ratio: float) -> float:
        if ratio <= 0:
            return _clamp_gain(cur)
        # ratio < 1 (해당 채널이 G보다 어둡다) → 게인을 1/ratio 배 올린다
        factor = 1.0 + strength * (1.0 / ratio - 1.0)
        return _clamp_gain(cur * factor)

    return _corrected(cur_r, report.ratio_r), _corrected(cur_b, report.ratio_b)


def _clamp_gain(value: float) -> float:
    return round(max(MIN_GAIN, min(MAX_GAIN, float(value))), 3)


# --- 이미지 읽기 --------------------------------------------------------


def sample_image_rgb(data: bytes, *, step: int = 8) -> ColorReport:
    """이미지 바이트 → ColorReport. JPEG 는 Pillow, PNG 는 Pillow 없어도 동작."""
    step = max(1, int(step))
    if data[:8] == PNG_SIGNATURE:
        try:
            return _sample_png(data, step=step)
        except ImageDecodeError:
            pass  # 인터레이스 등 자체 디코더가 못 읽는 PNG → Pillow 로 재시도
    return _sample_with_pillow(data, step=step)


def sample_image_file(path: str | Path, *, step: int = 8) -> ColorReport:
    return sample_image_rgb(Path(path).read_bytes(), step=step)


def _sample_with_pillow(data: bytes, *, step: int) -> ColorReport:
    try:
        from PIL import Image
    except Exception as exc:  # noqa: BLE001
        raise ImageDecodeError(
            "JPEG 색 분석에는 Pillow 가 필요합니다 "
            "(Pi: sudo apt install -y python3-pil, PC: pip install Pillow)"
        ) from exc
    import io

    with Image.open(io.BytesIO(data)) as img:
        rgb = img.convert("RGB")
        width, height = rgb.size
        loaded = rgb.load()
        pixels = [
            loaded[x, y]
            for y in range(0, height, step)
            for x in range(0, width, step)
        ]
    report = analyze_rgb(pixels)
    return ColorReport(**{**report.to_dict(), "width": width, "height": height})


def _sample_png(data: bytes, *, step: int) -> ColorReport:
    """quality.py 의 PNG 언필터를 재사용한 RGB 샘플러 (mock/PC 경로용)."""
    import zlib

    pos = 8
    width = height = depth = color_type = interlace = 0
    palette = b""
    idat = bytearray()
    while pos + 8 <= len(data):
        length = int.from_bytes(data[pos:pos + 4], "big")
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if len(body) != length:
            raise ImageDecodeError(f"청크 {tag!r} 가 잘렸습니다")
        pos += 12 + length
        if tag == b"IHDR":
            width = int.from_bytes(body[0:4], "big")
            height = int.from_bytes(body[4:8], "big")
            depth = body[8]
            color_type = body[9]
            interlace = body[12]
        elif tag == b"PLTE":
            palette = bytes(body)
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break

    if interlace != 0:
        raise ImageDecodeError("인터레이스 PNG 는 지원하지 않습니다")
    if depth not in (8, 16):
        raise ImageDecodeError(f"지원하지 않는 bit depth: {depth}")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise ImageDecodeError(f"지원하지 않는 color type: {color_type}")

    try:
        raw = zlib.decompress(bytes(idat))
    except zlib.error as exc:
        raise ImageDecodeError(f"IDAT 압축 해제 실패: {exc}") from exc

    bps = depth // 8
    bpp = channels * bps
    stride = width * bpp
    lines = _unfilter(raw, height, stride, bpp)

    pixels: list[tuple[int, int, int]] = []
    for y in range(0, height, step):
        base = y * stride
        for x in range(0, width, step):
            off = base + x * bpp
            if color_type in (2, 6):
                pixels.append((lines[off], lines[off + bps], lines[off + 2 * bps]))
            elif color_type in (0, 4):
                v = lines[off]
                pixels.append((v, v, v))
            else:
                idx = lines[off] * 3
                if idx + 2 >= len(palette):
                    raise ImageDecodeError(f"팔레트 인덱스 범위 초과: {lines[off]}")
                pixels.append((palette[idx], palette[idx + 1], palette[idx + 2]))

    report = analyze_rgb(pixels)
    return ColorReport(**{**report.to_dict(), "width": width, "height": height})
