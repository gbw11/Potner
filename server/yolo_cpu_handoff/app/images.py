"""Safe decoding for user-provided image payloads."""

from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
import warnings

from PIL import Image, ImageOps, UnidentifiedImageError

from app.errors import InvalidImageError, PayloadTooLargeError


_ACCEPTED_FORMATS = frozenset({"JPEG", "PNG", "WEBP"})


@dataclass(frozen=True)
class DecodedImage:
    image: Image.Image
    width: int
    height: int
    format: str


def decode_image_bytes(data: bytes, max_bytes: int) -> DecodedImage:
    """Verify and normalize a JPEG, PNG, or WebP payload into RGB pixels."""
    if len(data) > max_bytes:
        raise PayloadTooLargeError()
    if not data:
        raise InvalidImageError()

    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as probe:
                image_format = probe.format
                if image_format not in _ACCEPTED_FORMATS:
                    raise InvalidImageError()
                probe.verify()

            with Image.open(BytesIO(data)) as source:
                normalized = ImageOps.exif_transpose(source).convert("RGB")
                width, height = normalized.size
    except InvalidImageError:
        raise
    except (
        Image.DecompressionBombError,
        Image.DecompressionBombWarning,
        OSError,
        SyntaxError,
        UnidentifiedImageError,
        ValueError,
    ) as error:
        raise InvalidImageError() from error

    return DecodedImage(
        image=normalized,
        width=width,
        height=height,
        format=image_format,
    )


def decode_image_path(path: Path, max_bytes: int | None = None) -> DecodedImage:
    """Read and decode an image file, rejecting oversize files before reading."""
    try:
        if max_bytes is not None and path.stat().st_size > max_bytes:
            raise PayloadTooLargeError()
        if max_bytes is None:
            data = path.read_bytes()
        else:
            with path.open("rb") as image_file:
                data = image_file.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise PayloadTooLargeError()
    except PayloadTooLargeError:
        raise
    except OSError as error:
        raise InvalidImageError() from error

    return decode_image_bytes(data, max_bytes if max_bytes is not None else len(data))
