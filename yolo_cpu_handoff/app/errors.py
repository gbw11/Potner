"""Stable domain errors shared by the CLI and HTTP adapters."""


class HandoffError(Exception):
    """An expected handoff failure with transport-neutral metadata."""

    def __init__(self, code: str, message: str, exit_code: int, http_status: int):
        super().__init__(message)
        self.code = code
        self.message = message
        self.exit_code = exit_code
        self.http_status = http_status


class ConfigurationError(HandoffError):
    def __init__(self, message: str):
        super().__init__("INVALID_REQUEST", message, exit_code=2, http_status=400)


class InvalidRequestError(HandoffError):
    def __init__(self, message: str):
        super().__init__("INVALID_REQUEST", message, exit_code=2, http_status=400)


class InvalidImageError(HandoffError):
    def __init__(self, message: str = "Unable to decode image."):
        super().__init__("INVALID_IMAGE", message, exit_code=4, http_status=400)


class PayloadTooLargeError(HandoffError):
    def __init__(self, message: str = "Image exceeds the upload limit."):
        super().__init__("PAYLOAD_TOO_LARGE", message, exit_code=4, http_status=413)


class ModelNotReadyError(HandoffError):
    def __init__(self, message: str):
        super().__init__("MODEL_NOT_READY", message, exit_code=3, http_status=503)


class InferenceFailedError(HandoffError):
    def __init__(self, message: str = "Inference failed."):
        super().__init__("INFERENCE_FAILED", message, exit_code=5, http_status=500)
