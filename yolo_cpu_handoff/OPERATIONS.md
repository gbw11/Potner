# 운영 가이드

이 문서는 CPU 전용 YOLO FastAPI 서비스를 배포하고 Spring Boot에서 호출할 때의 런타임 계약과 점검 절차를 정의합니다. 모델 파일과 컨테이너 이미지를 함께 버전 관리하되, 요청 이미지와 추론 결과는 이 서비스의 영속 상태로 취급하지 않습니다. 현재 전달물은 내부 기술 인수인계용이며 `MODEL_CARD.md`와 `THIRD_PARTY_NOTICES.md`의 컴플라이언스 disposition 전에는 외부 또는 조직 경계 밖 네트워크에 배포하지 않습니다.

## 엔드포인트 의미

| 엔드포인트 | 성공 | 실패/의미 | 프로브 용도 |
|---|---|---|---|
| `GET /health` | `200 {"status":"up"}` | 프로세스가 HTTP 요청을 처리할 수 있는지만 확인합니다. 모델 로드 실패와 무관하게 `200`일 수 있습니다. | liveness |
| `GET /ready` | `200`과 `ready`, 가중치 파일명, `cpu`, 클래스 맵 | 모델 로드/준비 실패 시 `503 MODEL_NOT_READY`입니다. 절대 가중치 경로는 노출하지 않습니다. | readiness와 배포 승인 |
| `POST /predict` | `200`과 검출 JSON | multipart `file` 필수, `conf`와 `imgsz` 선택입니다. `400`, `413`, `503`, `500` 안정 오류를 반환합니다. | 실제 업무 트래픽 |

모든 응답에는 `X-Request-ID` 헤더가 있습니다. `/predict` 성공 본문과 모든 오류 본문에는 같은 `request_id`가 있지만, `/health`와 `/ready` 성공 본문에는 `request_id`가 없습니다. `/health` 성공이 트래픽 투입 가능을 뜻하지 않으므로 로드밸런서와 오케스트레이터의 준비성 판단에는 반드시 `/ready`를 사용합니다.

서비스는 업로드 이미지를 영속 저장하지 않습니다. 바깥 ASGI admission
layer가 multipart parser보다 먼저 raw body를 제한하고, 허용된 파일만
메모리에서 제한 크기까지 읽고 디코딩한 뒤 요청이 끝나면 참조를 버립니다.
API는 결과도 저장하지 않습니다. 보관이 필요하면 Spring Boot 측에서 명시적인
보존 기간, 접근 제어와 삭제 정책을 적용하십시오.

## 환경변수 계약

`.env.example`, `app/config.py`, `docker-compose.yml`의 현재 계약은 다음과 같습니다.

| 이름 | 기본값 | 허용값과 운영 의미 |
|---|---|---|
| `YOLO_WEIGHTS_PATH` | `/app/models/best.pt` | Ultralytics 호출 전에 존재하는 로컬 일반 파일인지 검사합니다. 로컬 파일 symlink는 허용하지만 누락 경로, 디렉터리, URL과 모델 alias는 거부합니다. 컨테이너에서는 읽기 전용 절대 경로를 사용하고 시작 전에 SHA-256을 검증합니다. |
| `YOLO_CONFIDENCE` | `0.10` | 부동소수점, `0.0` 이상 `1.0` 이하. 요청의 `conf`가 있으면 요청 값이 우선합니다. |
| `YOLO_IMAGE_SIZE` | `640` | 정수, `32` 이상 `4096` 이하. 요청의 `imgsz`가 있으면 요청 값이 우선합니다. |
| `YOLO_MAX_UPLOAD_MIB` | `10` | `0`보다 큰 정수 MiB. 파일 상한이며 raw request 상한은 이 값에 multipart framing용 정확히 1 MiB를 더합니다. |
| `YOLO_MAX_CONCURRENCY` | `1` | `0`보다 큰 정수. 한 Python worker 안에서 body 파싱 전에 admission할 전체 `/predict` 요청 수입니다. 먼저 `1`로 측정하십시오. |
| `YOLO_LOG_LEVEL` | `INFO` | CRITICAL, ERROR, WARNING, INFO, DEBUG 중 하나이며 대소문자를 정규화해 application logger에 적용합니다. |

추론 device는 환경변수가 아니라 CPU로 고정됩니다. JSON 값은 `cpu`이며 GPU 문자열을 전달하는 인터페이스는 없습니다.

`YOLO_LOG_LEVEL`은 `yolo_cpu_handoff.api` application logger에 적용됩니다.
Uvicorn 접근 로그는 별도 logger이므로 그 수준까지 바꿔야 하면 컨테이너
`CMD` 또는 실행 명령에 검증된 소문자 값을 명시하고 두 logger의 수준을
대시보드에 표시하십시오.

운영값 변경 전 다음을 확인합니다.

- Spring multipart 최대 파일 크기는 `YOLO_MAX_UPLOAD_MIB`와 맞추고 전체 요청 상한은 파일 상한 + 1 MiB 이하로 맞춥니다.
- `YOLO_IMAGE_SIZE`와 동시성을 올리면 CPU 시간과 메모리가 함께 증가하므로 부하 테스트 없이 변경하지 않습니다.
- `YOLO_WEIGHTS_PATH`가 실제 파일을 가리키고 실행 사용자가 읽을 수 있는지 확인합니다.
- 설정 오류는 애플리케이션 import/시작 자체를 실패시킬 수 있으므로 배포 전 동일 환경변수로 `GET /ready`까지 검증합니다.

## 로그와 개인정보

Python 앱은 모델 시작 실패와 추론/예상하지 못한 내부 실패에 제한된
구조화 진단 로그 record를 남깁니다. 필드는 `event`, 안정 `error_code`,
가능한 경우 `request_id`이며 진단 stack trace를 포함합니다. 클라이언트
응답은 상세 원인을 포함하지 않습니다. Uvicorn 접근 로그와 Spring 경계의
요청 로그는 별도로 수집하되 `request_id`로 연결합니다.

권장 필드:

- `timestamp`, `level`, `service`, `route`, `method`, `http_status`
- Python 응답의 `request_id`와 Spring의 `trace_id`
- 전체 요청 시간 `duration_ms`, 성공 시 Python의 `inference_ms`
- `detection_count`, 안정 `error_code`, 재시도 여부
- 업로드 바이트 수, 설정된 `imgsz`, 모델의 공개 버전/체크섬 식별자
- pod/container ID, CPU 제한과 배포 이미지 digest

개인정보 및 보안 규칙:

- 원본 이미지 바이트, multipart 본문, base64 이미지, 검출 crop을 로그에 남기지 않습니다.
- 사용자가 준 원본 파일명과 `source_name`은 개인정보를 포함할 수 있으므로 기본 로그에서 제외하거나 비가역 해시로 대체합니다.
- 가중치와 임시 파일의 절대 경로, Python traceback, stderr 원문, 환경변수 전체를 외부 응답이나 일반 INFO 로그에 남기지 않습니다. stack trace는 접근과 보존 기간이 제한된 ERROR 진단 채널에서만 다룹니다.
- 오류 응답은 안정 `code`, 안전한 `message`, `request_id`만 사용합니다. 상세 예외는 접근 제한된 진단 채널에서 보존 기간을 정해 관리합니다.
- 추론 서비스 자체는 업로드 이미지를 영속 저장하지 않으며, 디버깅 목적으로 저장 기능을 임의 추가하지 않습니다.

## 용량과 동시성

제공된 컨테이너는 `uvicorn app.api:app --workers 1`로 실행해야 하며 worker 옵션은 반드시 `--workers 1`을 유지합니다. 각 worker가 모델 사본을 메모리에 올리고 자기만의 `asyncio.Semaphore`를 만들기 때문에 worker를 늘리면 메모리와 실제 CPU 동시성이 함께 늘어납니다. 단일 컨테이너에서 worker 수로 확장하지 말고, 모델 한 사본이 들어가는 컨테이너를 복제해 수평 확장하십시오.

`YOLO_MAX_CONCURRENCY`는 한 worker 안에서 동시에 admission되는 전체
`/predict` 요청 수입니다. 바깥 ASGI layer가 body 파싱 전에 semaphore를
획득하고 업로드 읽기, 디코딩과 추론이 끝날 때까지, 그리고 응답 생성까지
slot을 유지합니다. 기본 `1`에서는 대기 요청이 Starlette multipart spool이나
디코딩 RGB 이미지를 먼저 만들지 않습니다. 대기 연결 자체의 무제한 유입은
막지 않으므로 ingress와 Spring에도 짧고 유한한 요청/큐 상한을 둡니다.

raw request body 상한은 다음과 같습니다.

```text
YOLO_MAX_UPLOAD_MIB × 1,048,576 + 1,048,576 bytes
```

마지막 1 MiB는 multipart framing과 `conf`/`imgsz` 필드용이며 실제 file
payload 상한을 늘리지 않습니다. `Content-Length`가 상한보다 크면 body를
읽지 않고 거부하고, header가 없거나 chunked이면 각 ASGI chunk를 누적해
multipart parser 완료 전에 `413 PAYLOAD_TOO_LARGE`로 중단합니다.

용량 측정 절차:

1. 운영과 같은 CPU quota, 메모리, 이미지 크기 분포, `imgsz`, 모델 체크섬으로 단일 요청 기준을 측정합니다.
2. 준비 완료 후 워밍업 요청을 제외하고 처리량, CPU 사용률, RSS, `inference_ms`와 end-to-end latency의 p50, p95, p99를 기록합니다.
3. `YOLO_MAX_CONCURRENCY=1`에서 부하를 단계적으로 올려 p95 지연과 타임아웃 비율이 급증하는 포화점을 찾습니다.
4. 동시성 `2` 이상은 별도 실험으로만 비교합니다. 처리량 증가 없이 p95와 CPU 경합만 늘면 `1`을 유지합니다.
5. 안전 처리량보다 낮은 per-replica 요청 상한과 짧고 유한한 대기열을 설정합니다. 초과 요청은 upstream에서 빠르게 거절하여 무제한 큐를 만들지 않습니다.

권장 지표와 알람:

- 요청 수/상태/안정 오류 코드별 비율, `INFERENCE_FAILED`와 `MODEL_NOT_READY` 비율
- `inference_ms` 및 전체 latency p50/p95/p99
- 진행 중 요청, semaphore 대기 시간/대기 수(계측 추가 필요), upstream 큐 깊이
- CPU 사용률/throttling, RSS/limit, 재시작/OOM 횟수
- `/health`와 `/ready` 성공률, 시작부터 ready까지 걸린 시간
- 활성 이미지 digest와 모델 SHA-256

`/health`는 짧은 liveness timeout으로 확인하되 모델이 큰 동안 충분한 `start_period`를 둡니다. `/ready` 실패 인스턴스에는 트래픽을 보내지 않습니다. 반복적인 liveness 재시작은 모델 로드 실패 원인을 가릴 수 있으므로 readiness와 분리합니다.

## Docker 재현성과 컴플라이언스 게이트

Dockerfile은 `python:3.11.9-slim-bookworm`과
`constraints-linux-cpu.txt`를 사용하고 CPU 전용
`torch==2.5.1+cpu`/`torchvision==0.20.1+cpu`를 먼저 설치합니다.
regular OpenCV가 slim에서 필요로 하는 `libgl1`, `libglib2.0-0`도
`--no-install-recommends`로 설치합니다. build 중 `cv2`, `ultralytics`,
`torch`를 import하고 `torch.version.cuda is None` 및
`torch.cuda.is_available() == false`를 assert합니다.

이 정적 구성과 build assertion은 실제 Linux x86_64 Docker runtime
검증을 대체하지 않습니다. 아래 보류 목록을 통과해야 합니다. 또한
Ultralytics/ultralytics-thop의 로컬 metadata는 AGPL-3.0을 표시합니다.
외부 배포나 조직 경계 밖 네트워크 운영 전 commercial/AGPL disposition과
데이터셋/모델 재배포 권리 소유자 승인을 문서로 완료하십시오. 이는 법률
자문이나 clearance 선언이 아닙니다.

## 안정 오류 계약

| 오류 코드 | HTTP 상태 | 안전 메시지와 조치 |
|---|---:|---|
| `INVALID_REQUEST` | `400` | 누락 필드에는 `Invalid request.`, 잘못된 옵션에는 검증 메시지를 반환합니다. 요청의 `conf`/`imgsz`와 multipart 필드를 확인합니다. |
| `INVALID_REQUEST` | `404` | `Resource not found.`입니다. 호출 경로와 base URL을 확인합니다. |
| `INVALID_IMAGE` | `400` | `Unable to decode image.`입니다. 실제 JPEG/PNG/WebP인지, 손상되지 않았는지 확인합니다. |
| `PAYLOAD_TOO_LARGE` | `413` | `Image exceeds the upload limit.`입니다. body 크기와 모든 계층의 제한을 확인합니다. |
| `MODEL_NOT_READY` | `503` | `The model is not ready.`입니다. 모델 파일, 체크섬, 권한, 시작 로그와 `/ready`를 확인합니다. |
| `INFERENCE_FAILED` | `500` | `Inference failed.`입니다. `request_id`, 모델 버전과 CPU/메모리 상태로 내부 진단합니다. |

CLI의 안정 종료 코드는 성공 `0`, 요청 `2`, 모델 준비 `3`, 이미지/크기 `4`, 추론 `5`, 배치 일부 실패 `6`입니다. Spring은 HTTP 상태나 프로세스 종료 코드만으로 합치지 말고 JSON의 안정 오류 코드를 함께 파싱해야 합니다.

## 롤포워드와 롤백

현재 전달 모델의 기준은 다음과 같습니다.

```text
5ab31994de572c21c6717f6741c23c765039427c6e428fbf44d9084543bc3707  best.pt
```

### 롤포워드

1. 새 `best.pt`의 SHA-256을 승인하고 `models/SHA256SUMS`를 같은 변경 단위로 갱신합니다.
2. CI에서 `(cd models && sha256sum -c SHA256SUMS)`, Python 전체 테스트와 실제 샘플 smoke 테스트를 실행합니다.
3. 커밋 SHA가 포함된 불변 이미지 태그로 빌드하고 가능하면 registry digest로 배포 선언을 고정합니다. `latest`만 사용하지 않습니다.
4. 새 replica를 트래픽 없이 시작하여 `/health`, `/ready`, 클래스 맵과 모델 파일명을 확인합니다.
5. 대표 이미지의 기대 가능한 스키마/범위를 확인한 뒤 canary 트래픽을 보내 오류율, p95, CPU와 메모리를 비교합니다.
6. 지표가 승인 범위이면 점진적으로 전환하고 이전 이미지 digest와 모델 파일을 롤백 기간 동안 보존합니다.

### 롤백

1. 트래픽 증가를 멈추고 이전 불변 이미지 태그/digest와 그 이미지가 승인한 모델 체크섬 조합을 선택합니다.
2. Compose bind mount를 사용한다면 이미지뿐 아니라 `best.pt`도 이전 체크섬 파일로 원자적으로 되돌립니다. 이미지와 모델을 서로 다른 버전으로 섞지 않습니다.
3. 새로 뜬 이전 replica의 `/ready`가 `200`인지 확인한 후 트래픽을 되돌립니다.
4. `request_id`, 오류율과 배포 타임라인을 보존하고 실패한 새 버전은 조사 전 덮어쓰지 않습니다.

## 문제 해결

### `/health`는 성공하지만 `/ready`가 `MODEL_NOT_READY`

- `(cd models && sha256sum -c SHA256SUMS)`를 실행합니다.
- `YOLO_WEIGHTS_PATH`가 컨테이너 내부 파일을 가리키는지, 비어 있지 않은지 확인합니다.
- Compose bind mount source가 존재하는 일반 파일인지, 컨테이너 사용자가 읽을 수 있는지 확인합니다.
- 호스트 절대 경로, traceback, 모델 내부 예외는 API가 의도적으로 숨기므로 제한된 시작 로그에서 조사합니다.
- 모델과 코드/Ultralytics 버전 조합이 승인된 이미지인지 확인합니다.

### `INVALID_REQUEST`

- multipart 필드명이 정확히 `file`인지 확인합니다.
- `conf`는 숫자이며 `0.0..1.0`, `imgsz`는 정수이며 `32..4096`인지 확인합니다.
- Spring이 JSON body가 아니라 `multipart/form-data`로 보내는지 확인합니다.
- `404`이면 `/predict` 경로와 프록시 prefix 재작성을 확인합니다.

### `INVALID_IMAGE`

- 확장자나 MIME만 바꾸지 말고 실제 내용을 확인합니다. 지원 형식은 JPEG, PNG, WebP입니다.
- 빈 파일, 잘린 파일, GIF와 디코더가 거부한 비정상 이미지를 제거합니다.
- Spring에서 multipart resource의 filename이 null이 되지 않는지 확인하되 파일명은 형식 판정의 근거가 아닙니다.

### `PAYLOAD_TOO_LARGE`

- 실제 파일 바이트가 `YOLO_MAX_UPLOAD_MIB × 1024 × 1024` 이하이고 전체 raw body가 그 값 + 1 MiB 이하인지 확인합니다.
- ingress, Spring multipart, WebClient 메모리 전략과 Python 제한을 비교합니다. 가장 작은 제한이 실제 외부 계약입니다.
- 제한을 올리기 전에 동시 업로드 시 메모리 사용량을 부하 테스트합니다.

### `INFERENCE_FAILED`

- 오류 응답의 `request_id`와 `X-Request-ID`를 Spring 구조화 로그에 함께 기록해 요청을 연결합니다. 현재 Uvicorn 접근 로그만으로는 이 ID를 검색할 수 없습니다.
- 같은 이미지의 민감 데이터를 복제하지 말고 승인된 비민감 테스트 샘플로 재현합니다.
- 모델 체크섬, 이미지 digest, CPU instruction/runtime 호환성, OOM/throttling과 최근 설정 변경을 확인합니다.
- 자동 무제한 재시도를 금지합니다. 동일 입력의 결정적 실패는 재시도로 회복되지 않습니다.

### 타임아웃 또는 지연 증가

- `inference_ms`와 전체 p95를 비교합니다. 차이가 크면 semaphore/upstream 큐, 업로드나 네트워크 대기 문제입니다.
- CPU throttling, replica당 진행 중 요청, `YOLO_MAX_CONCURRENCY`, 이미지 크기와 `imgsz` 분포를 확인합니다.
- timeout을 늘리기 전에 부하 유입을 제한하고 replica를 수평 확장합니다.
- Spring WebClient의 연결 타임아웃, 응답 타임아웃, 전체 timeout 중 어느 것이 발생했는지 구분합니다.

### CLI 프로세스가 남거나 호출이 멈춤

- Spring 구현이 stdout과 stderr를 동시에 drain한 뒤 `waitFor`하는지 확인합니다.
- 타임아웃 때 parent만이 아니라 `process.descendants()`까지 종료하는지 확인합니다.
- 인터럽트 상태 복구, UTF-8 디코딩, `finally` 임시 파일 삭제가 구현됐는지 확인합니다.
- 상시 요청 경로라면 CLI 대신 FastAPI로 전환합니다.

### Docker 보류 검증 목록

이 환경에서는 Docker 런타임을 검증하지 못했습니다. Docker가 설치된 Linux 호스트에서 다음 명령을 순서대로 실행하고 결과를 배포 증적으로 보관해야 합니다.

```bash
(cd models && sha256sum -c SHA256SUMS)
docker compose config
docker compose build --no-cache
docker compose up -d
docker compose ps
docker compose exec yolo python -c "import cv2, torch, ultralytics; assert torch.version.cuda is None; assert not torch.cuda.is_available()"
curl -fS http://localhost:8000/health
curl -fS http://localhost:8000/ready
curl -sS -X POST http://localhost:8000/predict \
  -F 'file=@/absolute/path/to/approved-sample.jpg;type=image/jpeg' \
  -F 'conf=0.10' \
  -F 'imgsz=640'
docker compose logs --tail=200 yolo
docker compose down
```

확인 항목은 이미지 빌드 성공, pinned CPU closure와 native library import,
CUDA wheel 부재, 컨테이너 비루트 실행, 포트 노출, 모델 read-only mount,
healthcheck 전이, `/ready` 모델 정보, 예측 JSON 스키마, 종료 시 orphan
프로세스 부재입니다. 실패하면 컨테이너 로그와 `docker inspect` 결과에서
비밀값과 호스트 절대 경로를 제거한 후 공유하십시오.
