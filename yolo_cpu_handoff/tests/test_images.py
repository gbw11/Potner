from io import BytesIO

import pytest
from PIL import Image

from app.errors import InvalidImageError, PayloadTooLargeError
from app.images import decode_image_bytes, decode_image_path


@pytest.fixture
def jpeg_bytes():
    image = Image.new("RGB", (3, 2), "red")
    image.getexif()[274] = 6
    output = BytesIO()
    image.save(output, format="JPEG", exif=image.getexif())
    return output.getvalue()


@pytest.fixture
def png_bytes():
    output = BytesIO()
    Image.new("RGB", (2, 1), "blue").save(output, format="PNG")
    return output.getvalue()


@pytest.fixture
def gif_bytes():
    output = BytesIO()
    Image.new("RGB", (2, 1), "green").save(output, format="GIF")
    return output.getvalue()


def test_decode_applies_exif_and_returns_rgb(jpeg_bytes):
    decoded = decode_image_bytes(jpeg_bytes, max_bytes=1024 * 1024)

    assert decoded.image.mode == "RGB"
    assert (decoded.width, decoded.height) == (2, 3)
    assert decoded.format == "JPEG"


def test_decode_accepts_actual_webp_and_preserves_its_format():
    output = BytesIO()
    Image.new("RGBA", (2, 1), "yellow").save(output, format="WEBP")

    decoded = decode_image_bytes(output.getvalue(), max_bytes=1024 * 1024)

    assert decoded.image.mode == "RGB"
    assert decoded.format == "WEBP"


def test_rejects_unsupported_gif(gif_bytes):
    with pytest.raises(InvalidImageError):
        decode_image_bytes(gif_bytes, max_bytes=1024 * 1024)


def test_rejects_empty_payload():
    with pytest.raises(InvalidImageError):
        decode_image_bytes(b"", max_bytes=1024 * 1024)


def test_rejects_oversized_payload(png_bytes):
    with pytest.raises(PayloadTooLargeError):
        decode_image_bytes(png_bytes, max_bytes=len(png_bytes) - 1)


def test_path_checks_size_before_reading(tmp_path, monkeypatch):
    path = tmp_path / "image.png"
    path.write_bytes(b"not read")

    def fail_if_read(_path):
        raise AssertionError("oversized file was read")

    monkeypatch.setattr(type(path), "read_bytes", fail_if_read)

    with pytest.raises(PayloadTooLargeError):
        decode_image_path(path, max_bytes=1)


def test_path_uses_bounded_read_when_file_grows_after_stat(tmp_path, monkeypatch):
    path = tmp_path / "image.png"
    path.write_bytes(b"a")
    max_bytes = 4

    class BoundedReader:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, size):
            assert size == max_bytes + 1
            return b"abcdef"[:size]

    monkeypatch.setattr(type(path), "read_bytes", lambda _path: pytest.fail("unbounded read"))
    monkeypatch.setattr(type(path), "open", lambda _path, mode: BoundedReader())

    with pytest.raises(PayloadTooLargeError):
        decode_image_path(path, max_bytes=max_bytes)


def test_invalid_content_maps_to_domain_error():
    with pytest.raises(InvalidImageError):
        decode_image_bytes(b"not an image", max_bytes=1024 * 1024)
