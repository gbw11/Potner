"""촬영된 이미지의 밝기 검사 (저조도 / 정상 / 과노출 판정).

`camera.exposure_value` / `camera.brightness` 는 Picamera2 에 넘기는 **촬영 파라미터**다.
이 모듈은 그 반대편 — 촬영이 **끝난 뒤** 결과 이미지가 실제로 너무 어둡거나 밝지 않은지
판정한다. 설정은 `camera.quality:` 아래에 따로 둔다.

판정 기준은 두 축을 함께 본다 (히스토그램 기반):
  1) 평균 밝기(luma 0~255)가 min_mean_brightness ~ max_mean_brightness 범위인가
  2) 극단 픽셀 비율 — dark_pixel_level 이하 픽셀 비율 / bright_pixel_level 이상 픽셀 비율
     (평균은 멀쩡한데 절반이 새까맣게 뭉개진 야간 프레임을 평균만으로는 못 잡는다)

의존성: 표준 라이브러리만으로 PNG 를 직접 디코드한다 (mock 경로 · 테스트용).
JPEG 등 그 외 포맷은 Pillow 가 설치돼 있으면 자동으로 사용한다. Pillow 가 없어도
이 모듈은 **import 되고**, 실제로 JPEG 를 검사하려는 시점에 `ImageDecodeError` 로
명확히 실패한다. 디코더를 직접 주입할 수도 있다 (`decoder=` 인자).

호출부 배선(통과한 이미지만 후속 단계로) 은 별도 티켓 — 여기서는 판정 결과와
`should_deliver()` / `ensure_quality()` 만 제공한다. 재시도 루프도 여기서 돌지 않고,
`report.needs_recapture` 로 "재촬영이 필요하다" 는 사실만 알려 준다.
"""

from __future__ import annotations

import logging
import zlib
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional, Sequence

logger = logging.getLogger(__name__)


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

VERDICT_OK = "ok"
VERDICT_TOO_DARK = "too_dark"
VERDICT_TOO_BRIGHT = "too_bright"
VERDICT_UNKNOWN = "unknown"
VERDICT_SKIPPED = "skipped"

_VERDICT_KO = {
    VERDICT_OK: "정상",
    VERDICT_TOO_DARK: "저조도",
    VERDICT_TOO_BRIGHT: "과노출",
    VERDICT_UNKNOWN: "분석 실패",
    VERDICT_SKIPPED: "검사 안 함",
}


class BrightnessCheckError(Exception):
    """밝기 검사 계열 오류의 베이스."""


class ImageDecodeError(BrightnessCheckError):
    """이미지 픽셀을 읽지 못함 (포맷 미지원 · 손상 · 디코더 없음)."""


class BrightnessRejected(BrightnessCheckError):
    """밝기 검사 실패로 후속 단계 전달이 거부됨 (예외 스타일 호출부용)."""

    def __init__(self, report: "BrightnessReport") -> None:
        super().__init__(f"밝기 검사 실패: {report.verdict} ({report.reason})")
        self.report = report


# --- 설정 ---------------------------------------------------------------


@dataclass(frozen=True)
class BrightnessThresholds:
    """`camera.quality:` 설정값. 기준을 바꾸려면 yaml 을 고친다 (코드 하드코딩 금지)."""

    enabled: bool = True
    min_mean_brightness: float = 40.0      # 이보다 어두우면 저조도
    max_mean_brightness: float = 215.0     # 이보다 밝으면 과노출
    dark_pixel_level: int = 16             # 이 값 이하 = "뭉개진 검정"
    bright_pixel_level: int = 240          # 이 값 이상 = "날아간 흰색"
    max_dark_ratio: float = 0.60           # 검정 픽셀 허용 비율
    max_bright_ratio: float = 0.50         # 흰색 픽셀 허용 비율
    sample_step: int = 4                   # N픽셀마다 1개만 샘플링 (FHD 순수 파이썬 대응)
    reject_on_failure: bool = True         # False 면 경고만 하고 후속 단계로 통과시킨다

    @classmethod
    def from_config(cls, config: Optional[dict[str, Any]]) -> "BrightnessThresholds":
        """전체 config dict 또는 `camera.quality` 서브 dict 를 받는다."""
        section = _quality_section(config)
        defaults = cls()
        on_failure = str(section.get("on_failure", "reject" if defaults.reject_on_failure else "warn"))
        return cls(
            enabled=bool(section.get("enabled", defaults.enabled)),
            min_mean_brightness=float(
                section.get("min_mean_brightness", defaults.min_mean_brightness)
            ),
            max_mean_brightness=float(
                section.get("max_mean_brightness", defaults.max_mean_brightness)
            ),
            dark_pixel_level=int(section.get("dark_pixel_level", defaults.dark_pixel_level)),
            bright_pixel_level=int(
                section.get("bright_pixel_level", defaults.bright_pixel_level)
            ),
            max_dark_ratio=float(section.get("max_dark_ratio", defaults.max_dark_ratio)),
            max_bright_ratio=float(section.get("max_bright_ratio", defaults.max_bright_ratio)),
            sample_step=max(1, int(section.get("sample_step", defaults.sample_step))),
            reject_on_failure=on_failure.strip().lower() != "warn",
        )


def _quality_section(config: Optional[dict[str, Any]]) -> dict[str, Any]:
    if not config:
        return {}
    camera = config.get("camera")
    if isinstance(camera, dict):
        quality = camera.get("quality")
        return dict(quality) if isinstance(quality, dict) else {}
    return dict(config)


# --- 판정 결과 ----------------------------------------------------------


@dataclass(frozen=True)
class BrightnessReport:
    verdict: str
    label_ko: str
    mean_brightness: Optional[float]
    dark_ratio: Optional[float]
    bright_ratio: Optional[float]
    sampled_pixels: int
    reason: str
    width: Optional[int] = None
    height: Optional[int] = None
    source: Optional[str] = None
    delivery_allowed: bool = True

    @property
    def ok(self) -> bool:
        """AI 분석/업로드에 쓸 만한 밝기인가."""
        return self.verdict in (VERDICT_OK, VERDICT_SKIPPED)

    @property
    def needs_recapture(self) -> bool:
        """재촬영으로 나아질 여지가 있는 실패인가 (재시도 루프는 호출부 몫)."""
        return self.verdict in (VERDICT_TOO_DARK, VERDICT_TOO_BRIGHT, VERDICT_UNKNOWN)

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["ok"] = self.ok
        data["needsRecapture"] = self.needs_recapture
        return data


@dataclass
class DecodedImage:
    """디코더 출력 — 픽셀당 luma(0~255) 한 개씩."""

    width: int
    height: int
    luma: Sequence[int] = field(default_factory=list)


Decoder = Callable[[bytes], DecodedImage]


# --- 디코더 -------------------------------------------------------------


def _rgb_luma(r: int, g: int, b: int) -> int:
    # Rec.601 휘도. 정수 근사 (부동소수 곱 60만 번보다 빠르다)
    return (r * 299 + g * 587 + b * 114) // 1000


def decode_png_luma(data: bytes) -> DecodedImage:
    """순수 표준 라이브러리 PNG 디코더 (bit depth 8/16, color type 0·2·3·4·6, non-interlaced)."""
    if data[:8] != PNG_SIGNATURE:
        raise ImageDecodeError("PNG 시그니처가 아닙니다")

    pos = 8
    width = height = 0
    depth = color_type = 0
    interlace = 0
    palette = b""
    idat = bytearray()
    saw_ihdr = False

    try:
        while pos + 8 <= len(data):
            length = int.from_bytes(data[pos:pos + 4], "big")
            tag = data[pos + 4:pos + 8]
            body = data[pos + 8:pos + 8 + length]
            if len(body) != length:
                raise ImageDecodeError(f"청크 {tag!r} 가 잘렸습니다")
            pos += 12 + length  # length + tag + data + crc
            if tag == b"IHDR":
                width = int.from_bytes(body[0:4], "big")
                height = int.from_bytes(body[4:8], "big")
                depth = body[8]
                color_type = body[9]
                interlace = body[12]
                saw_ihdr = True
            elif tag == b"PLTE":
                palette = bytes(body)
            elif tag == b"IDAT":
                idat += body
            elif tag == b"IEND":
                break
    except IndexError as exc:  # 잘린 헤더
        raise ImageDecodeError(f"PNG 헤더 파싱 실패: {exc}") from exc

    if not saw_ihdr:
        raise ImageDecodeError("IHDR 청크가 없습니다")
    if interlace != 0:
        raise ImageDecodeError("인터레이스 PNG 는 지원하지 않습니다")
    if depth not in (8, 16):
        raise ImageDecodeError(f"지원하지 않는 bit depth: {depth} (8 또는 16만 지원)")
    if width <= 0 or height <= 0:
        raise ImageDecodeError(f"잘못된 이미지 크기: {width}x{height}")

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise ImageDecodeError(f"지원하지 않는 color type: {color_type}")
    if color_type == 3 and depth != 8:
        raise ImageDecodeError(f"팔레트 PNG 는 bit depth 8만 지원합니다 (got {depth})")
    if color_type == 3 and not palette:
        raise ImageDecodeError("팔레트 PNG 에 PLTE 청크가 없습니다")

    try:
        raw = zlib.decompress(bytes(idat))
    except zlib.error as exc:
        raise ImageDecodeError(f"IDAT 압축 해제 실패: {exc}") from exc

    bytes_per_sample = depth // 8
    bpp = channels * bytes_per_sample
    stride = width * bpp
    expected = (stride + 1) * height
    if len(raw) < expected:
        raise ImageDecodeError(f"픽셀 데이터가 부족합니다 ({len(raw)} < {expected})")

    lines = _unfilter(raw, height, stride, bpp)
    luma = _lines_to_luma(lines, width, height, stride, channels, bytes_per_sample, color_type, palette)
    return DecodedImage(width=width, height=height, luma=luma)


def _unfilter(raw: bytes, height: int, stride: int, bpp: int) -> bytearray:
    out = bytearray()
    prev = bytearray(stride)
    pos = 0
    for row in range(height):
        filter_type = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if filter_type == 0:
            pass
        elif filter_type == 1:  # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filter_type == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filter_type == 3:  # Average
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                up = prev[i]
                upleft = prev[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(left, up, upleft)) & 0xFF
        else:
            raise ImageDecodeError(f"알 수 없는 필터 타입 {filter_type} (row {row})")
        out += line
        prev = line
    return out


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _lines_to_luma(
    lines: bytearray,
    width: int,
    height: int,
    stride: int,
    channels: int,
    bytes_per_sample: int,
    color_type: int,
    palette: bytes,
) -> list[int]:
    """언필터된 스캔라인 → 픽셀당 luma. depth 16 은 상위 바이트만 쓴다."""
    luma: list[int] = []
    step = channels * bytes_per_sample
    for y in range(height):
        base = y * stride
        for x in range(width):
            off = base + x * step
            if color_type == 0 or color_type == 4:          # gray / gray+alpha
                luma.append(lines[off])
            elif color_type == 2 or color_type == 6:        # rgb / rgba
                luma.append(
                    _rgb_luma(
                        lines[off],
                        lines[off + bytes_per_sample],
                        lines[off + 2 * bytes_per_sample],
                    )
                )
            else:                                            # palette
                idx = lines[off] * 3
                if idx + 2 >= len(palette):
                    raise ImageDecodeError(f"팔레트 인덱스 범위 초과: {lines[off]}")
                luma.append(_rgb_luma(palette[idx], palette[idx + 1], palette[idx + 2]))
    return luma


def pillow_available() -> bool:
    try:
        import PIL.Image  # noqa: F401
    except Exception:  # noqa: BLE001 — Pillow 미설치/설치 파손 모두 "없음"
        return False
    return True


def decode_with_pillow(data: bytes) -> DecodedImage:
    """JPEG 등 PNG 이외 포맷용. Pillow 가 있어야만 동작한다."""
    try:
        from PIL import Image
    except Exception as exc:  # noqa: BLE001
        raise ImageDecodeError(
            "JPEG 등 PNG 이외 이미지를 검사하려면 Pillow 가 필요합니다 "
            "(Pi: sudo apt install -y python3-pil, PC: pip install Pillow)"
        ) from exc
    import io

    try:
        with Image.open(io.BytesIO(data)) as img:
            grey = img.convert("L")
            return DecodedImage(width=grey.width, height=grey.height, luma=list(grey.getdata()))
    except ImageDecodeError:
        raise
    except Exception as exc:  # noqa: BLE001 — Pillow 내부 예외를 우리 예외로 통일
        raise ImageDecodeError(f"Pillow 디코드 실패: {exc}") from exc


def decode_luma(data: bytes) -> DecodedImage:
    """기본 디코더: PNG 는 표준 라이브러리, 나머지는 Pillow(있으면)."""
    if not data:
        raise ImageDecodeError("빈 파일입니다")
    if data[:8] == PNG_SIGNATURE:
        try:
            return decode_png_luma(data)
        except ImageDecodeError:
            if pillow_available():
                return decode_with_pillow(data)
            raise
    if pillow_available():
        return decode_with_pillow(data)
    raise ImageDecodeError(
        "PNG 가 아닌 이미지는 Pillow 없이 디코드할 수 없습니다 "
        "(Pi: sudo apt install -y python3-pil, PC: pip install Pillow)"
    )


# --- 분석 · 판정 --------------------------------------------------------


def analyze_luma(
    values: Sequence[int],
    *,
    thresholds: Optional[BrightnessThresholds] = None,
    width: Optional[int] = None,
    height: Optional[int] = None,
    source: Optional[str] = None,
) -> BrightnessReport:
    """luma(0~255) 시퀀스 → 판정. 파일·하드웨어 없이 단위 테스트 가능한 순수 함수."""
    th = thresholds or BrightnessThresholds()
    if not th.enabled:
        return _report(
            VERDICT_SKIPPED, None, None, None, 0, "밝기 검사 비활성(camera.quality.enabled=false)",
            width, height, source, th,
        )

    sampled = values[:: th.sample_step] if th.sample_step > 1 else values
    total = len(sampled)
    if total == 0:
        return _report(
            VERDICT_UNKNOWN, None, None, None, 0, "분석할 픽셀이 없습니다",
            width, height, source, th,
        )

    dark = 0
    bright = 0
    accumulated = 0
    for value in sampled:
        accumulated += value
        if value <= th.dark_pixel_level:
            dark += 1
        elif value >= th.bright_pixel_level:
            bright += 1

    mean = accumulated / total
    dark_ratio = dark / total
    bright_ratio = bright / total

    if mean < th.min_mean_brightness:
        verdict = VERDICT_TOO_DARK
        reason = f"평균 밝기 {mean:.1f} < 하한 {th.min_mean_brightness:.1f}"
    elif mean > th.max_mean_brightness:
        verdict = VERDICT_TOO_BRIGHT
        reason = f"평균 밝기 {mean:.1f} > 상한 {th.max_mean_brightness:.1f}"
    elif dark_ratio > th.max_dark_ratio:
        verdict = VERDICT_TOO_DARK
        reason = (
            f"어두운 픽셀 비율 {dark_ratio:.2f} > 허용 {th.max_dark_ratio:.2f} "
            f"(<= {th.dark_pixel_level})"
        )
    elif bright_ratio > th.max_bright_ratio:
        verdict = VERDICT_TOO_BRIGHT
        reason = (
            f"밝은 픽셀 비율 {bright_ratio:.2f} > 허용 {th.max_bright_ratio:.2f} "
            f"(>= {th.bright_pixel_level})"
        )
    else:
        verdict = VERDICT_OK
        reason = f"평균 밝기 {mean:.1f} (정상 범위)"

    return _report(
        verdict, mean, dark_ratio, bright_ratio, total, reason, width, height, source, th,
    )


def _report(
    verdict: str,
    mean: Optional[float],
    dark_ratio: Optional[float],
    bright_ratio: Optional[float],
    sampled: int,
    reason: str,
    width: Optional[int],
    height: Optional[int],
    source: Optional[str],
    thresholds: BrightnessThresholds,
) -> BrightnessReport:
    passed = verdict in (VERDICT_OK, VERDICT_SKIPPED)
    return BrightnessReport(
        verdict=verdict,
        label_ko=_VERDICT_KO.get(verdict, verdict),
        mean_brightness=mean,
        dark_ratio=dark_ratio,
        bright_ratio=bright_ratio,
        sampled_pixels=sampled,
        reason=reason,
        width=width,
        height=height,
        source=source,
        delivery_allowed=passed or not thresholds.reject_on_failure,
    )


def analyze_image_bytes(
    data: bytes,
    *,
    thresholds: Optional[BrightnessThresholds] = None,
    decoder: Optional[Decoder] = None,
    source: Optional[str] = None,
) -> BrightnessReport:
    """이미 메모리에 있는 이미지 바이트를 판정. 디코드 실패는 ImageDecodeError."""
    th = thresholds or BrightnessThresholds()
    if not th.enabled:
        return analyze_luma([], thresholds=th, source=source)
    decoded = (decoder or decode_luma)(data)
    return analyze_luma(
        decoded.luma,
        thresholds=th,
        width=decoded.width,
        height=decoded.height,
        source=source,
    )


def analyze_image(
    path: str | Path,
    *,
    thresholds: Optional[BrightnessThresholds] = None,
    decoder: Optional[Decoder] = None,
) -> BrightnessReport:
    """이미지 파일 경로를 판정. 읽기/디코드 실패는 ImageDecodeError 로 올린다."""
    file_path = Path(path)
    try:
        data = file_path.read_bytes()
    except OSError as exc:
        raise ImageDecodeError(f"이미지를 읽을 수 없습니다 ({file_path}): {exc}") from exc
    return analyze_image_bytes(
        data, thresholds=thresholds, decoder=decoder, source=str(file_path)
    )


# --- 호출부용 진입점 ----------------------------------------------------


def check_image(
    path: str | Path,
    config: Optional[dict[str, Any]] = None,
    *,
    thresholds: Optional[BrightnessThresholds] = None,
    decoder: Optional[Decoder] = None,
) -> BrightnessReport:
    """검사 + 로그까지 하는 고수준 진입점. **예외를 던지지 않는다.**

    정상 INFO / 저조도·과노출 WARNING / 분석 실패 ERROR 로 남긴다.
    분석 자체가 실패하면 verdict=unknown 리포트를 돌려주므로, 호출부는
    `report.ok` 와 `should_deliver(report)` 만 보면 된다.
    """
    th = thresholds or BrightnessThresholds.from_config(config)
    try:
        report = analyze_image(path, thresholds=th, decoder=decoder)
    except BrightnessCheckError as exc:
        report = _report(
            VERDICT_UNKNOWN, None, None, None, 0, str(exc), None, None, str(path), th,
        )
    _log_report(report)
    return report


def _log_report(report: BrightnessReport) -> None:
    where = report.source or "<memory>"
    if report.verdict == VERDICT_SKIPPED:
        logger.debug(f"[quality] 밝기 검사 생략: {where}")
    elif report.verdict == VERDICT_OK:
        logger.info(f"[quality] 밝기 정상: {where} — {report.reason}")
    elif report.verdict == VERDICT_UNKNOWN:
        logger.error(f"[quality] 밝기 분석 실패: {where} — {report.reason}")
    else:
        logger.warning(
            f"[quality] 밝기 이상({report.label_ko}): {where} — {report.reason} "
            f"/ 재촬영 필요={report.needs_recapture}"
        )


def should_deliver(report: BrightnessReport) -> bool:
    """이 이미지를 후속 AI 분석·서버 업로드로 넘겨도 되는가.

    `camera.quality.on_failure: warn` 이면 실패해도 True (경고만 남기고 통과).
    """
    return report.delivery_allowed


def ensure_quality(
    path: str | Path,
    config: Optional[dict[str, Any]] = None,
    *,
    thresholds: Optional[BrightnessThresholds] = None,
    decoder: Optional[Decoder] = None,
) -> BrightnessReport:
    """예외 스타일 진입점 — 전달 불가면 BrightnessRejected 를 던진다."""
    report = check_image(path, config, thresholds=thresholds, decoder=decoder)
    if not should_deliver(report):
        raise BrightnessRejected(report)
    return report
