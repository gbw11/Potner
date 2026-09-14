import hashlib
import json
from pathlib import Path
import re
import tomllib

import pytest

from app.api import _SAFE_ERROR_MESSAGES
from app.cli import _IMAGE_SUFFIXES
from app.config import SETTINGS_ENV_NAMES, Settings
from app.errors import (
    InferenceFailedError,
    InvalidImageError,
    InvalidRequestError,
    ModelNotReadyError,
    PayloadTooLargeError,
)
from app.images import _ACCEPTED_FORMATS
from app.schemas import (
    BoundingBox,
    Detection,
    ImageInfo,
    ModelInfo,
    PredictionResponse,
)


ROOT = Path(__file__).parents[1]


def _read(name: str) -> str:
    return (ROOT / name).read_text(encoding="utf-8")


def _env_defaults() -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in _read(".env.example").splitlines()
        if line and not line.startswith("#")
    )


def _json_example(document: str, heading: str) -> dict:
    match = re.search(
        rf"^### {re.escape(heading)}\n+```json\n(.*?)\n```",
        document,
        flags=re.MULTILINE | re.DOTALL,
    )
    assert match, f"{heading!r} JSON example is missing"
    return json.loads(match.group(1))


def _java_error_map(document: str, name: str) -> dict[int, set[str]]:
    match = re.search(
        rf"{name}\s*=\s*Map\.of\((.*?)\n\s*\);",
        document,
        flags=re.DOTALL,
    )
    assert match, f"{name} Java map is missing"
    result: dict[int, set[str]] = {}
    for status, codes in re.findall(
        r"(\d+),\s*Set\.of\(([^)]*)\)",
        match.group(1),
    ):
        result[int(status)] = set(re.findall(r'"([A-Z_]+)"', codes))
    return result


@pytest.mark.parametrize(
    ("path", "required_headings"),
    [
        (
            "README.md",
            [
                "# YOLO CPU 추론 핸드오프",
                "## 빠른 시작",
                "## CLI",
                "## HTTP API",
                "## Docker와 Compose",
                "## 파일 목록",
            ],
        ),
        (
            "SPRING_BOOT_INTEGRATION.md",
            [
                "# Spring Boot 3 통합",
                "## 통합 방식 선택",
                "## 공통 JSON DTO",
                "## ProcessBuilder 통합",
                "## WebClient 통합",
            ],
        ),
        (
            "OPERATIONS.md",
            [
                "# 운영 가이드",
                "## 엔드포인트 의미",
                "## 환경변수 계약",
                "## 용량과 동시성",
                "## 롤포워드와 롤백",
                "## 문제 해결",
            ],
        ),
    ],
)
def test_handoff_docs_have_navigable_required_sections(path, required_headings):
    text = _read(path)

    assert all(heading in text for heading in required_headings)


def test_readme_install_contract_matches_package_and_cpu_image():
    readme = _read("README.md")
    project = tomllib.loads(_read("pyproject.toml"))["project"]
    dockerfile = _read("Dockerfile")
    constraints = _read("constraints-linux-cpu.txt")

    assert project["requires-python"] == ">=3.11"
    assert "Python 3.11 이상" in readme
    assert "https://download.pytorch.org/whl/cpu" in readme
    for requirement in ("torch==2.5.1+cpu", "torchvision==0.20.1+cpu"):
        assert requirement in dockerfile
        assert requirement in constraints
        assert requirement in readme
    package_install = (
        "python -m pip install --constraint constraints-linux-cpu.txt -e ."
    )
    assert readme.index("torch==2.5.1+cpu") < readme.index(package_install)
    assert "--constraint constraints-linux-cpu.txt" in readme
    assert package_install in readme


def test_readme_uses_real_input_placeholders_and_one_decimal_timing():
    readme = _read("README.md")

    assert "./samples" not in readme
    assert "/absolute/path/to/your-image.jpg" in readme
    assert "/absolute/path/to/images" in readme
    assert "실제 입력 파일" in readme
    success = _json_example(readme, "성공 응답 예시")
    assert success["inference_ms"] == 87.4
    assert '"inference_ms": 87.4' in readme


def test_readme_cli_exit_contract_matches_domain_errors():
    readme = _read("README.md")
    errors = [
        InvalidRequestError("Invalid request."),
        ModelNotReadyError("The model is not ready."),
        InvalidImageError(),
        PayloadTooLargeError(),
        InferenceFailedError(),
    ]

    assert "python -m app.cli predict" in readme
    assert "python -m app.cli batch" in readme
    assert "| `0` | 성공 |" in readme
    for error in errors:
        assert f"| `{error.exit_code}` | `{error.code}` |" in readme
    assert "| `6` | 배치 일부 실패 |" in readme
    assert "stdout에는 JSON 한 건" in readme
    assert "JSONL" in readme


def test_readme_api_examples_match_response_schema_and_safe_error():
    readme = _read("README.md")
    success = _json_example(readme, "성공 응답 예시")
    failure = _json_example(readme, "오류 응답 예시")

    assert set(success) == set(PredictionResponse.model_fields)
    assert set(success["image"]) == set(ImageInfo.model_fields)
    assert set(success["model"]) == set(ModelInfo.model_fields)
    assert success["model"]["device"] == "cpu"
    assert set(success["detections"][0]) == set(Detection.model_fields)
    assert set(success["detections"][0]["bbox"]) == set(BoundingBox.model_fields)
    PredictionResponse.model_validate(success)
    assert failure == {
        "error": {
            "code": "INVALID_IMAGE",
            "message": _SAFE_ERROR_MESSAGES["INVALID_IMAGE"],
            "request_id": "4adf15b7-19e8-4e2f-aa5d-905eaf8dfe84",
        }
    }
    assert "curl -fS http://localhost:8000/health" in readme
    assert "curl -fS http://localhost:8000/ready" in readme
    assert "curl -sS -X POST http://localhost:8000/predict" in readme


def test_readme_checksum_formats_persistence_and_manifest_are_exact():
    readme = _read("README.md")
    checksum_line = _read("models/SHA256SUMS").strip()
    digest, filename = checksum_line.split()
    actual_digest = hashlib.sha256((ROOT / "models" / filename).read_bytes()).hexdigest()

    assert digest == actual_digest
    assert digest == "5ab31994de572c21c6717f6741c23c765039427c6e428fbf44d9084543bc3707"
    assert digest in readme
    assert "(cd models && sha256sum -c SHA256SUMS)" in readme
    assert "sha256sum -c models/SHA256SUMS" not in readme
    for image_format in _ACCEPTED_FORMATS:
        assert image_format.casefold() in readme.casefold()
    for suffix in _IMAGE_SUFFIXES:
        assert suffix in readme
    assert "추론 요청과 업로드 이미지를 영속 저장하지 않습니다" in readme
    expected_manifest = (
        "app/",
        "models/best.pt",
        "models/SHA256SUMS",
        "tests/",
        ".dockerignore",
        ".env.example",
        "constraints-linux-cpu.txt",
        "pyproject.toml",
        "Dockerfile",
        "docker-compose.yml",
        "MODEL_CARD.md",
        "THIRD_PARTY_NOTICES.md",
        "README.md",
        "SPRING_BOOT_INTEGRATION.md",
        "OPERATIONS.md",
    )
    for path in expected_manifest:
        assert f"`{path}`" in readme
        assert (ROOT / path.removesuffix("/")).exists()

    manifest_section = readme.split("## 파일 목록", maxsplit=1)[1]
    documented_paths = set(
        re.findall(r"^\| `([^`]+)` \|", manifest_section, flags=re.MULTILINE)
    )
    assert documented_paths == set(expected_manifest)


def test_compliance_artifacts_preserve_unknown_rights_gate():
    readme = _read("README.md")
    model_card = _read("MODEL_CARD.md")
    notices = _read("THIRD_PARTY_NOTICES.md")

    for required in (
        "내부 기술 인수인계",
        "컴플라이언스 승인 전",
        "[`MODEL_CARD.md`](MODEL_CARD.md)",
        "[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)",
    ):
        assert required in readme
    for required in (
        "5ab31994de572c21c6717f6741c23c765039427c6e428fbf44d9084543bc3707",
        "germination",
        "vegetative",
        "flowering",
        "plant_growth_yolo26n",
        "yolo26n.pt",
        "general-plant-growth-stage-model",
        "UNKNOWN",
        "데이터셋/모델 재배포",
        "소유자 승인",
    ):
        assert required in model_card
    for required in (
        "로컬 설치 메타데이터",
        "ultralytics",
        "8.4.104",
        "AGPL-3.0",
        "외부 배포",
        "네트워크 배포",
        "commercial",
        "법률 자문이 아닙니다",
        "승인되었다는 의미가 아닙니다",
    ):
        assert required in notices
    assert not (ROOT / "LICENSE").exists()


def test_readme_marks_docker_validation_as_pending_and_recommends_fastapi():
    readme = _read("README.md")

    assert "docker build -t yolo-cpu-handoff:local ." in readme
    assert "docker compose up --build" in readme
    assert "이 환경에서는 Docker 런타임을 검증하지 못했습니다." in readme
    assert "상시 트래픽에는 FastAPI" in readme


def test_spring_records_cover_every_nested_json_field():
    integration = _read("SPRING_BOOT_INTEGRATION.md")
    required_declarations = [
        "record PredictionResponse(",
        '@JsonProperty("request_id") String requestId',
        '@JsonProperty("source_name") String sourceName',
        "ImageInfo image",
        "ModelInfo model",
        "List<Detection> detections",
        '@JsonProperty("detection_count") int detectionCount',
        '@JsonProperty("inference_ms") double inferenceMs',
        "record ImageInfo(int width, int height)",
        "record ModelInfo(",
        '@JsonProperty("confidence_threshold") double confidenceThreshold',
        "record Detection(",
        '@JsonProperty("class_id") int classId',
        '@JsonProperty("class_name") String className',
        "BoundingBox bbox",
        "record BoundingBox(double x1, double y1, double x2, double y2)",
        "record ErrorResponse(ErrorBody error)",
        "record ErrorBody(",
    ]

    assert all(declaration in integration for declaration in required_declarations)
    assert "ObjectMapper" in integration
    assert "readValue" in integration
    assert "FAIL_ON_MISSING_CREATOR_PROPERTIES" in integration
    assert "FAIL_ON_NULL_FOR_PRIMITIVES" in integration
    assert "validatePrediction" in integration
    assert "validateError" in integration
    assert "detectionCount() != response.detections().size()" in integration


def test_processbuilder_example_has_safe_lifecycle_and_exit_mapping():
    integration = _read("SPRING_BOOT_INTEGRATION.md")

    for required in (
        "List<String> command",
        "new ProcessBuilder(command)",
        "redirectErrorStream(false)",
        "StandardCharsets.UTF_8",
        "stdoutFuture",
        "stderrFuture",
        "process.waitFor(waitNanos, TimeUnit.NANOSECONDS)",
        "process.descendants()",
        "filter(ProcessHandle::isAlive)",
        "destroyForcibly()",
        "MAX_STDOUT_BYTES",
        "MAX_STDERR_BYTES",
        "StreamCapture",
        "Files.deleteIfExists(tempImage)",
        "toAbsolutePath().normalize()",
        "isAbsolute()",
        "Duration",
        "case 2",
        "case 3",
        "case 4",
        "case 5",
    ):
        assert required in integration
    for unsafe in ('List.of("sh", "-c"', 'List.of("/bin/sh", "-c"', "cmd.exe /c"):
        assert unsafe not in integration
    assert "셸을 사용하지" in integration
    assert "stdout과 stderr를 동시에" in integration
    assert "finally" in integration
    assert "readAllBytes()" not in integration


def test_processbuilder_uses_one_deadline_and_unconditional_bounded_cleanup():
    integration = _read("SPRING_BOOT_INTEGRATION.md")

    for required in (
        "long deadlineNanos",
        "remainingNanos(deadlineNanos)",
        "CompletableFuture.allOf(stdoutFuture, stderrFuture)",
        "ConcurrentHashMap.newKeySet()",
        "observeDescendants",
        "cleanupProcess(",
        "closeProcessStreams",
        "stdoutFuture.cancel(true)",
        "stderrFuture.cancel(true)",
        "trackedHandles",
    ):
        assert required in integration
    assert "get(5, SECONDS)" not in integration
    assert "if (process != null && process.isAlive())" not in integration
    assert "cleanupDeadline" not in integration
    assert "CLEANUP_TIMEOUT_NANOS" not in integration
    assert integration.count("System.nanoTime() +") == 1
    assert re.search(
        r"cleanupProcess\(\s*process,\s*trackedHandles,\s*"
        r"stdoutFuture,\s*stderrFuture,\s*deadlineNanos\s*\)",
        integration,
    )
    assert "waitForTrackedHandles(trackedHandles, deadlineNanos)" in integration
    assert "부모 프로세스가 먼저 종료" in integration
    assert "플랫폼" in integration


def test_processbuilder_bounds_uploads_processes_and_drain_threads():
    integration = _read("SPRING_BOOT_INTEGRATION.md")

    for required in (
        "MAX_UPLOAD_BYTES = 10L * 1024 * 1024",
        "image.getSize() > MAX_UPLOAD_BYTES",
        "copyBounded",
        "MAX_UPLOAD_BYTES + 1",
        "Semaphore",
        "tryAcquire",
        "processSlots.release()",
        "max-file-size: 10485760B",
        "max-request-size: 11534336B",
    ):
        assert required in integration
    assert re.search(
        r"newFixedThreadPool\(\s*MAX_CONCURRENT_PROCESSES \* 2",
        integration,
    )
    assert "Files.copy(input, tempImage" not in integration
    assert "newCachedThreadPool" not in integration


def test_java_clients_enforce_error_code_transport_matrices(tmp_path):
    integration = _read("SPRING_BOOT_INTEGRATION.md")
    errors = [
        InvalidRequestError("Invalid request."),
        InvalidImageError(),
        PayloadTooLargeError(),
        ModelNotReadyError("The model is not ready."),
        InferenceFailedError(),
    ]

    for error in errors:
        assert f"| `{error.exit_code}` | `{error.code}` |" in integration
        assert f"| `{error.http_status}` | `{error.code}` |" in integration
    expected_cli: dict[int, set[str]] = {}
    expected_http: dict[int, set[str]] = {}
    for error in errors:
        expected_cli.setdefault(error.exit_code, set()).add(error.code)
        expected_http.setdefault(error.http_status, set()).add(error.code)

    from fastapi.testclient import TestClient

    from app.api import create_app

    class LoadablePredictor:
        def load(self):
            pass

    settings = Settings(
        weights_path=str(tmp_path / "best.pt"),
        confidence=0.10,
        image_size=640,
        max_upload_bytes=10 * 1024 * 1024,
        max_concurrency=1,
        log_level="INFO",
    )
    with TestClient(
        create_app(settings=settings, predictor=LoadablePredictor())
    ) as client:
        unknown_route = client.get("/not-a-real-route")
    assert unknown_route.status_code == 404
    assert unknown_route.json()["error"]["code"] == "INVALID_REQUEST"
    assert unknown_route.json()["error"]["message"] == "Resource not found."
    expected_http.setdefault(unknown_route.status_code, set()).add(
        unknown_route.json()["error"]["code"]
    )

    assert _java_error_map(integration, "PREDICT_ERROR_CODES") == expected_cli
    assert _java_error_map(integration, "HTTP_ERROR_CODES") == expected_http
    assert "| `404` | `INVALID_REQUEST` |" in integration
    for required in (
        "PREDICT_ERROR_CODES",
        "HTTP_ERROR_CODES",
        "fromPredict",
        "validateHttpError",
        "CliExit.PROTOCOL_FAILURE",
        "YoloProtocolException",
        "allowedCodes.contains(code)",
    ):
        assert required in integration
    assert "case 6 -> PARTIAL_BATCH_FAILURE" not in integration
    assert "success/error body mismatch" in integration


def test_webclient_example_covers_multipart_limits_timeouts_and_error_parsing():
    integration = _read("SPRING_BOOT_INTEGRATION.md")

    for required in (
        "WebClient",
        "MultipartBodyBuilder",
        "ByteArrayResource",
        "getFilename()",
        "CONNECT_TIMEOUT_MILLIS",
        "responseTimeout(Duration",
        "maxInMemorySize",
        "exchangeToMono",
        "ErrorResponse",
        "PredictionResponse",
        "FileSystemResource",
    ):
        assert required in integration
    assert "연결 타임아웃" in integration
    assert "응답 타임아웃" in integration
    assert "최대 버퍼" in integration


def test_webclient_ready_method_and_spring_health_indicator_are_complete():
    integration = _read("SPRING_BOOT_INTEGRATION.md")

    for required in (
        "public Mono<ReadyResponse> ready()",
        '.uri("/ready")',
        "parseReadyResponse(",
        "YoloDtos.validateReady(",
        "ReactiveHealthIndicator",
        "class YoloReadinessHealthIndicator",
        "public Mono<Health> health()",
        "management.endpoint.health.probes.enabled: true",
        "include: readinessState,yoloReadiness",
    ):
        assert required in integration
    assert "Java 17 컴파일 검증은 보류" in integration


def test_operations_environment_table_matches_runtime_defaults(monkeypatch):
    operations = _read("OPERATIONS.md")
    defaults = _env_defaults()

    assert set(defaults) == set(SETTINGS_ENV_NAMES)
    for name in SETTINGS_ENV_NAMES:
        monkeypatch.delenv(name, raising=False)
    settings = Settings.from_env()
    runtime_defaults = {
        "YOLO_WEIGHTS_PATH": settings.weights_path,
        "YOLO_CONFIDENCE": f"{settings.confidence:.2f}",
        "YOLO_IMAGE_SIZE": str(settings.image_size),
        "YOLO_MAX_UPLOAD_MIB": str(settings.max_upload_bytes // 1024 // 1024),
        "YOLO_MAX_CONCURRENCY": str(settings.max_concurrency),
        "YOLO_LOG_LEVEL": settings.log_level,
    }
    assert defaults == runtime_defaults
    for name, default in defaults.items():
        assert f"| `{name}` | `{default}` |" in operations
    for boundary in ("`0.0` 이상 `1.0` 이하", "`32` 이상 `4096` 이하"):
        assert boundary in operations
    assert "CRITICAL, ERROR, WARNING, INFO, DEBUG" in operations
    assert "CPU로 고정" in operations


def test_operations_cover_endpoint_concurrency_privacy_and_capacity():
    operations = _read("OPERATIONS.md")

    for required in (
        "`GET /health`",
        "`GET /ready`",
        "`POST /predict`",
        "`--workers 1`",
        "`YOLO_MAX_CONCURRENCY`",
        "asyncio.Semaphore",
        "request_id",
        "inference_ms",
        "detection_count",
        "원본 이미지 바이트",
        "절대 경로",
        "준비성",
        "p95",
    ):
        assert required in operations
    assert "업로드 이미지를 영속 저장하지" in operations
    assert (
        "`/predict` 성공 본문과 모든 오류 본문에는 같은 `request_id`"
        in operations
    )
    assert (
        "`/health`와 `/ready` 성공 본문에는 `request_id`가 없습니다"
        in operations
    )
    assert "Python 접근 로그와 연결합니다" not in operations
    for required in (
        "1 MiB",
        "multipart framing",
        "body 파싱 전에",
        "디코딩과 추론이 끝날 때까지",
        "YOLO_LOG_LEVEL",
        "구조화 진단 로그",
        "로컬 일반 파일",
        "constraints-linux-cpu.txt",
        "libgl1",
        "libglib2.0-0",
        "컴플라이언스",
    ):
        assert required in operations


def test_operations_error_table_matches_stable_http_contract():
    operations = _read("OPERATIONS.md")
    errors = [
        InvalidRequestError("Invalid request."),
        InvalidImageError(),
        PayloadTooLargeError(),
        ModelNotReadyError("The model is not ready."),
        InferenceFailedError(),
    ]

    for error in errors:
        assert f"| `{error.code}` | `{error.http_status}` |" in operations
        if error.code in _SAFE_ERROR_MESSAGES:
            assert _SAFE_ERROR_MESSAGES[error.code] in operations
    assert "| `INVALID_REQUEST` | `404` |" in operations


def test_operations_rollout_and_pending_docker_checks_are_actionable():
    operations = _read("OPERATIONS.md")
    checksum = _read("models/SHA256SUMS").split()[0]

    assert checksum in operations
    assert "(cd models && sha256sum -c SHA256SUMS)" in operations
    assert "sha256sum -c models/SHA256SUMS" not in operations
    for command in (
        "docker compose config",
        "docker compose build --no-cache",
        "docker compose up -d",
        "docker compose ps",
        "docker compose logs --tail=200 yolo",
        "docker compose down",
    ):
        assert command in operations
    for term in ("불변 이미지 태그", "롤포워드", "롤백", "MODEL_NOT_READY"):
        assert term in operations
