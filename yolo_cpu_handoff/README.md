# YOLO CPU 추론 핸드오프

이 디렉터리는 GPU 없이 CPU에서 YOLO 모델을 실행하는 독립 패키지입니다. 한 장 또는 디렉터리 단위 CLI와 FastAPI HTTP API를 제공합니다. Spring Boot 3 연동은
[`SPRING_BOOT_INTEGRATION.md`](SPRING_BOOT_INTEGRATION.md), 운영 계약은
[`OPERATIONS.md`](OPERATIONS.md)를 참고하십시오.

> 이 패키지는 **내부 기술 인수인계**용입니다. 컴플라이언스 승인 전에는 외부
> 배포하거나 조직 경계를 넘는 네트워크 서비스로 운영하지 마십시오. 모델
> provenance와 미확정 권리는 [`MODEL_CARD.md`](MODEL_CARD.md), 로컬
> 의존성 metadata와 AGPL/commercial 보류 게이트는
> [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)에 기록했습니다.

## 빠른 시작

### 사전 조건

- Linux 또는 macOS
- Python 3.11 이상
- 모델 파일 `models/best.pt`
- HTTP 예제를 실행할 경우 `curl`

프로젝트 루트, 즉 이 `README.md`가 있는 디렉터리에서 실행합니다.
`constraints-linux-cpu.txt`는 Linux x86_64 CPU 런타임 closure를 고정합니다.
PyTorch가 일반 인덱스에서 GPU 빌드로 바뀌지 않도록 **CPU 전용 인덱스에서
정확한 wheel을 먼저 설치한 다음 같은 constraints로 패키지를 설치합니다.**

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install \
  --index-url https://download.pytorch.org/whl/cpu \
  --constraint constraints-linux-cpu.txt \
  --no-deps \
  torch==2.5.1+cpu torchvision==0.20.1+cpu
python -m pip install --constraint constraints-linux-cpu.txt -e .
```

개발 테스트 의존성까지 필요하면 기본 설치 뒤 다음 명령을 추가합니다.

```bash
python -m pip install --constraint constraints-linux-cpu.txt -e ".[dev]"
pytest
```

모델 무결성을 확인하고 로컬 절대 경로를 설정합니다.

```bash
(cd models && sha256sum -c SHA256SUMS)
export YOLO_WEIGHTS_PATH="$PWD/models/best.pt"
```

정상 체크섬은 다음과 같습니다.

```text
5ab31994de572c21c6717f6741c23c765039427c6e428fbf44d9084543bc3707  best.pt
```

## CLI

### 단일 이미지

`predict` 성공 시 stdout에는 JSON 한 건과 줄바꿈만 출력합니다. 모델 라이브러리 진단은 stderr로 분리됩니다. 아래 placeholder를 실제 입력 파일의 절대 경로로 바꾸십시오.

```bash
python -m app.cli predict /absolute/path/to/your-image.jpg \
  --weights "$PWD/models/best.pt" \
  --conf 0.10 \
  --imgsz 640
```

`--weights`, `--conf`, `--imgsz`를 생략하면 각각 `YOLO_WEIGHTS_PATH`,
`YOLO_CONFIDENCE`, `YOLO_IMAGE_SIZE` 값을 사용합니다.

### 배치

`batch`는 입력 디렉터리를 재귀 탐색하고 경로를 결정적으로 정렬하여 JSONL 파일을 새로 씁니다. 지원하지 않는 확장자는 건너뜁니다. 한 이미지가 실패해도 나머지는 처리하며, 실패 행에는 동일한 오류 JSON을 기록합니다. 아래 placeholder를 실제 입력 이미지 디렉터리의 절대 경로로 바꾸십시오.

```bash
python -m app.cli batch /absolute/path/to/images \
  --output ./predictions.jsonl \
  --weights "$PWD/models/best.pt" \
  --conf 0.10 \
  --imgsz 640
```

### CLI 종료 코드

| 종료 코드 | 의미 |
|---:|---|
| `0` | 성공 |
| `2` | `INVALID_REQUEST` |
| `3` | `MODEL_NOT_READY` |
| `4` | `INVALID_IMAGE` |
| `4` | `PAYLOAD_TOO_LARGE` |
| `5` | `INFERENCE_FAILED` |
| `6` | 배치 일부 실패 |

사용법 오류와 도메인 오류도 stdout에 정확히 한 개의 오류 JSON을 씁니다. 배치에서 `6`은 JSONL에 성공/실패 결과가 모두 기록되었음을 뜻합니다. 실행 프로세스의 stderr는 진단용이며 JSON 계약으로 파싱하지 마십시오.

## HTTP API

로컬 서버는 모델을 한 번 로드하고 한 worker 안에서 재사용합니다.

```bash
export YOLO_WEIGHTS_PATH="$PWD/models/best.pt"
uvicorn app.api:app --host 127.0.0.1 --port 8000 --workers 1
```

상태 확인:

```bash
curl -fS http://localhost:8000/health
curl -fS http://localhost:8000/ready
```

추론 요청의 multipart 필드명은 `file`, 선택 필드명은 `conf`와 `imgsz`입니다.
아래 placeholder를 실제 입력 파일의 절대 경로로 바꾸십시오.

```bash
curl -sS -X POST http://localhost:8000/predict \
  -F 'file=@/absolute/path/to/your-image.jpg;type=image/jpeg' \
  -F 'conf=0.10' \
  -F 'imgsz=640'
```

모든 HTTP 응답에는 `X-Request-ID` 헤더가 있습니다. `/predict` 성공 본문과 모든 오류 본문의 `request_id`는 이 헤더와 같습니다. `/health`와 `/ready` 성공 본문에는 `request_id`가 없으므로 헤더를 Spring Boot 로그 상관관계 키로 사용하십시오.

`POST /predict`의 raw request body 상한은 파일 상한
`YOLO_MAX_UPLOAD_MIB × 1024 × 1024`에 multipart framing용 **정확히 1 MiB
(1,048,576 bytes)**를 더한 값입니다. 기본값에서는 파일 10 MiB, 전체 raw
body 11 MiB입니다. 이 바깥 ASGI 제한은 `Content-Length`가 없고 chunked인
stream도 multipart parser 전에 누적 검사하며 초과 시 안정적인
`413 PAYLOAD_TOO_LARGE`를 반환합니다. 파싱 뒤에도 실제 파일은 원래 10 MiB
상한을 별도로 적용하므로 framing 여유가 파일 제한을 늘리지 않습니다.

같은 바깥 admission layer가 body 파싱 전에 `YOLO_MAX_CONCURRENCY` slot을
획득하고 업로드 읽기, 디코딩, CPU 추론과 응답 생성까지 보유합니다. 따라서
기본값 `1`에서는 대기 요청이 multipart 임시 파일이나 디코딩 이미지를 먼저
만들지 않습니다.

### 성공 응답 예시

```json
{
  "request_id": "d6612a01-a76b-4458-b956-b193f0821944",
  "source_name": "plant.jpg",
  "image": {
    "width": 1920,
    "height": 1080
  },
  "model": {
    "weights": "best.pt",
    "device": "cpu",
    "imgsz": 640,
    "confidence_threshold": 0.1
  },
  "detections": [
    {
      "class_id": 1,
      "class_name": "vegetative",
      "confidence": 0.9321,
      "bbox": {
        "x1": 112.5,
        "y1": 74.0,
        "x2": 804.25,
        "y2": 921.5
      }
    }
  ],
  "detection_count": 1,
  "inference_ms": 87.4
}
```

위 수치는 응답 형태를 보여 주는 예시이며 실제 검출값은 이미지와 모델에 따라 다릅니다.

### 오류 응답 예시

```json
{
  "error": {
    "code": "INVALID_IMAGE",
    "message": "Unable to decode image.",
    "request_id": "4adf15b7-19e8-4e2f-aa5d-905eaf8dfe84"
  }
}
```

| HTTP 상태 | 오류 코드 | 의미 |
|---:|---|---|
| `400` | `INVALID_REQUEST` | 누락/형식 오류 또는 옵션 범위 오류 |
| `400` | `INVALID_IMAGE` | 디코딩할 수 없거나 지원하지 않는 이미지 |
| `413` | `PAYLOAD_TOO_LARGE` | 업로드 제한 초과 |
| `503` | `MODEL_NOT_READY` | 모델 로드 또는 준비 실패 |
| `500` | `INFERENCE_FAILED` | 추론 실패 |

알 수 없는 경로는 `404`와 `INVALID_REQUEST`를 반환합니다. 자세한 메시지와 운영 조치는
[`OPERATIONS.md`](OPERATIONS.md)의 오류 표를 따릅니다.

### 지원 형식과 저장 정책

지원 형식은 JPEG(`.jpg`, `.jpeg`), PNG(`.png`), WebP(`.webp`)입니다. MIME 문자열이나 확장자만 신뢰하지 않고 Pillow로 실제 내용을 디코딩합니다. GIF 등 다른 형식은 지원하지 않습니다.

API는 추론 요청과 업로드 이미지를 영속 저장하지 않습니다. CLI `predict`도 입력 파일을 수정하지 않습니다. CLI `batch`만 호출자가 지정한 `--output` JSONL 결과 파일을 생성하거나 덮어씁니다.

모델 로더는 `YOLO_WEIGHTS_PATH`가 실제 로컬 일반 파일(로컬 파일을 가리키는
symlink 포함)인지 Ultralytics 호출 전에 검사합니다. 누락 경로, 디렉터리,
URL과 모델 alias는 다운로드를 시도하지 않고 `MODEL_NOT_READY`가 됩니다.

애플리케이션 logger는 `YOLO_LOG_LEVEL`을 사용합니다. 모델 로드, 추론 및
예상하지 못한 내부 실패는 `event`, 안정 오류 코드, 가능한 경우
`request_id`와 stack trace가 있는 제한된 구조화 진단 record로 남습니다.
이미지 바이트와 원본 파일명은 기록하지 않으며 클라이언트 응답은 계속
안전한 고정 메시지만 반환합니다.

상시 트래픽에는 FastAPI 방식을 권장합니다. 모델을 프로세스 시작 시 한 번 로드해 재사용하고 바깥 admission semaphore로 전체 `/predict` 수명주기의 CPU/메모리 동시성을 제한하기 때문입니다. `ProcessBuilder` 방식은 저빈도 배치나 격리된 관리 작업에 적합하지만 요청마다 Python 시작과 모델 로드 비용이 듭니다.

## Docker와 Compose

> 이 환경에서는 Docker 런타임을 검증하지 못했습니다. 아래 명령은 제공된 `Dockerfile`과 `docker-compose.yml`의 실행 절차이며, 운영 반영 전에 Docker가 있는 호스트에서 [`OPERATIONS.md`](OPERATIONS.md)의 보류 검증 목록을 실행하십시오.

이미지 빌드와 직접 실행:

```bash
docker build -t yolo-cpu-handoff:local .
docker run --rm \
  -p 8000:8000 \
  --mount type=bind,source="$PWD/models/best.pt",target=/app/models/best.pt,readonly \
  yolo-cpu-handoff:local
```

Compose 실행:

```bash
docker compose up --build
```

다른 터미널에서 확인합니다.

```bash
curl -fS http://localhost:8000/health
curl -fS http://localhost:8000/ready
```

종료:

```bash
docker compose down
```

컨테이너는 `python:3.11.9-slim-bookworm`, 비루트 사용자, 고정된 CPU 전용
PyTorch/TorchVision, 포트 `8000`, Uvicorn worker `1`로 실행됩니다.
regular OpenCV가 slim에서 요구하는 `libgl1`, `libglib2.0-0`을 설치하고
빌드 중 `cv2`, `ultralytics`, `torch` import와 CUDA 비활성 상태를
assert합니다. Compose는 호스트의 `models/best.pt`를 읽기 전용으로
마운트합니다. 정적 검사는 통과 조건의 일부일 뿐이며 Docker Linux x86_64
runtime gate는 여전히 보류입니다.

## 파일 목록

| 경로 | 역할 |
|---|---|
| `app/` | 설정, 이미지 검증, 추론, JSON 스키마, CLI와 FastAPI 구현 |
| `models/best.pt` | 배포할 YOLO 가중치 |
| `models/SHA256SUMS` | 가중치 SHA-256 기준값 |
| `tests/` | 단위, API, CLI, 문서 계약 및 선택적 smoke 테스트 |
| `.dockerignore` | Docker build context에서 개발/cache 산출물 제외 |
| `.env.example` | 환경변수 기본값 예시 |
| `constraints-linux-cpu.txt` | Linux x86_64 CPU 런타임 의존성 고정 |
| `pyproject.toml` | Python 3.11+ 패키지와 의존성 정의 |
| `Dockerfile` | CPU 전용 비루트 런타임 이미지 |
| `docker-compose.yml` | 단일 worker 서비스, 모델 마운트와 healthcheck |
| `MODEL_CARD.md` | 모델 식별, 한계, 학습 provenance와 권리 보류 |
| `THIRD_PARTY_NOTICES.md` | 제3자 metadata와 AGPL/commercial disposition 보류 |
| `README.md` | 설치, CLI, API, 컨테이너 빠른 시작 |
| `SPRING_BOOT_INTEGRATION.md` | Java 17 / Spring Boot 3 통합 코드 |
| `OPERATIONS.md` | 설정, 용량, 관측, 배포와 문제 해결 |

실제 모델을 로드하는 선택적 smoke 테스트는 샘플 이미지 절대 경로를 지정해 실행합니다.

```bash
YOLO_SMOKE_IMAGE=/absolute/path/to/sample.jpg pytest -m smoke -v
```
