"""Machine-readable command-line adapter for CPU-only YOLO inference."""

from contextlib import redirect_stdout
from dataclasses import replace
from pathlib import Path
import sys
from typing import Annotated

import click
import typer
from typer.main import get_command

from .config import PredictionOptions, Settings
from .errors import ConfigurationError, HandoffError, InvalidRequestError
from .images import decode_image_path
from .inference import YoloPredictor
from .schemas import ErrorBody, ErrorResponse


app = typer.Typer(add_completion=False, no_args_is_help=False)
_IMAGE_SUFFIXES = frozenset({".jpg", ".jpeg", ".png", ".webp"})


def _error_json(error: HandoffError) -> str:
    return ErrorResponse(
        error=ErrorBody(code=error.code, message=error.message)
    ).model_dump_json()


def _emit_error_and_exit(error: HandoffError) -> None:
    typer.echo(_error_json(error))
    raise typer.Exit(code=error.exit_code)


def _exit_invalid_usage(message: str) -> None:
    error = InvalidRequestError(message)
    typer.echo(_error_json(error))
    typer.echo(message, err=True)
    raise SystemExit(error.exit_code)


def _settings_and_options(
    weights: Path | None,
    confidence: float | None,
    image_size: int | None,
) -> tuple[Settings, PredictionOptions]:
    settings = Settings.from_env()
    selected_confidence = (
        settings.confidence if confidence is None else confidence
    )
    selected_image_size = settings.image_size if image_size is None else image_size
    try:
        options = PredictionOptions(
            confidence=selected_confidence,
            image_size=selected_image_size,
        )
    except ValueError as error:
        raise ConfigurationError(str(error)) from error

    return (
        replace(
            settings,
            weights_path=(
                settings.weights_path if weights is None else str(weights)
            ),
            confidence=selected_confidence,
            image_size=selected_image_size,
        ),
        options,
    )


def _loaded_predictor(settings: Settings) -> YoloPredictor:
    # Third-party model diagnostics belong on stderr. This also protects the
    # JSON-only stdout contract if a backend writes directly to stdout.
    with redirect_stdout(sys.stderr):
        predictor = YoloPredictor(settings)
        predictor.load()
    return predictor


def _predict_json(
    predictor: YoloPredictor,
    image_path: Path,
    settings: Settings,
    options: PredictionOptions,
) -> str:
    decoded = decode_image_path(image_path, settings.max_upload_bytes)
    with redirect_stdout(sys.stderr):
        response = predictor.predict(decoded, image_path.name, options)
    return response.model_dump_json()


def _output_aliases_input(output: Path, image_paths: list[Path]) -> bool:
    output_resolved = output.resolve(strict=False)
    output_exists = output.exists()
    for image_path in image_paths:
        if output_resolved == image_path.resolve(strict=False):
            return True
        if output_exists:
            try:
                if output.samefile(image_path):
                    return True
            except OSError:
                pass
    return False


@app.command()
def predict(
    image: Annotated[Path, typer.Argument(help="Image to classify.")],
    weights: Annotated[
        Path | None, typer.Option("--weights", help="YOLO weights path.")
    ] = None,
    confidence: Annotated[
        float | None, typer.Option("--conf", help="Confidence threshold.")
    ] = None,
    image_size: Annotated[
        int | None, typer.Option("--imgsz", help="Inference image size.")
    ] = None,
) -> None:
    """Predict one image and print exactly one JSON response."""
    try:
        settings, options = _settings_and_options(
            weights, confidence, image_size
        )
        decoded = decode_image_path(image, settings.max_upload_bytes)
        predictor = _loaded_predictor(settings)
        with redirect_stdout(sys.stderr):
            response = predictor.predict(decoded, image.name, options)
        typer.echo(response.model_dump_json())
    except HandoffError as error:
        _emit_error_and_exit(error)


@app.command()
def batch(
    directory: Annotated[
        Path, typer.Argument(help="Directory of images to classify.")
    ],
    output: Annotated[
        Path, typer.Option("--output", help="Destination JSONL file.")
    ],
    weights: Annotated[
        Path | None, typer.Option("--weights", help="YOLO weights path.")
    ] = None,
    confidence: Annotated[
        float | None, typer.Option("--conf", help="Confidence threshold.")
    ] = None,
    image_size: Annotated[
        int | None, typer.Option("--imgsz", help="Inference image size.")
    ] = None,
) -> None:
    """Recursively predict supported images into deterministic JSONL."""
    try:
        settings, options = _settings_and_options(
            weights, confidence, image_size
        )
        if not directory.is_dir():
            raise InvalidRequestError("Batch input must be a directory.")
        image_paths = sorted(
            (
                path
                for path in directory.rglob("*")
                if path.is_file() and path.suffix.lower() in _IMAGE_SUFFIXES
            ),
            key=lambda path: (
                path.relative_to(directory).as_posix().casefold(),
                path.relative_to(directory).as_posix(),
            ),
        )
        if _output_aliases_input(output, image_paths):
            raise InvalidRequestError(
                "Batch output must not alias an input image."
            )
        predictor = _loaded_predictor(settings)
        had_failure = False
        try:
            with output.open("w", encoding="utf-8", newline="\n") as stream:
                for image_path in image_paths:
                    try:
                        record = _predict_json(
                            predictor, image_path, settings, options
                        )
                    except HandoffError as error:
                        record = _error_json(error)
                        had_failure = True
                    stream.write(record)
                    stream.write("\n")
        except OSError as error:
            raise InvalidRequestError(
                "Unable to write the batch output file."
            ) from error
        if had_failure:
            raise typer.Exit(code=6)
    except HandoffError as error:
        _emit_error_and_exit(error)


def main() -> None:
    """Run Typer while preserving the JSON contract for parser failures."""
    arguments = sys.argv[1:]
    if not arguments:
        _exit_invalid_usage("A command is required.")

    try:
        exit_code = get_command(app).main(
            args=arguments,
            prog_name="python -m app.cli",
            standalone_mode=False,
        )
        if isinstance(exit_code, int):
            raise SystemExit(exit_code)
    except click.UsageError as error:
        _exit_invalid_usage(str(error))
    except click.exceptions.Exit as error:
        raise SystemExit(error.exit_code) from error


if __name__ == "__main__":
    main()
