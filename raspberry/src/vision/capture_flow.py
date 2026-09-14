"""촬영 재시도 흐름 — 촬영 함수 1개를 "성공할 때까지 N번" 감싸는 계층.

스트림 B(촬영 파이프라인)와 C(밝기 검사)를 엮는 자리다. 촬영 자체(`capture_fn`)도,
밝기 판정(`quality.py`)도 여기서 새로 구현하지 않는다. 여기가 하는 일은 세 가지뿐:

  1. 1회 시도를 "촬영 → 저장 확인 → 밝기 검사" 로 묶어 성공/실패를 한 가지 형태
     (`AttemptOutcome`)로 정규화한다.
  2. 실패를 **재시도 대상 / 대상 아님** 으로 분류한다.
  3. 대상이면 정책(`RetryPolicy`)이 정한 횟수·간격만큼 다시 찍는다.

재시도 대상 (다시 찍으면 결과가 달라질 수 있는 실패):
  - 카메라 예외 (CAMERA_ERROR)            — 드라이버 일시 오류, 센서 타임아웃 등
  - 촬영 실패 보고 (CAPTURE_FAILED)        — `CaptureResult.ok == False`
  - 이미지 저장 실패 (IMAGE_SAVE_FAILED)   — 파일이 없거나 0바이트, 쓰기 OSError
  - 밝기 검사 탈락 (IMAGE_TOO_DARK / IMAGE_TOO_BRIGHT / IMAGE_QUALITY_UNKNOWN)

재시도 대상 아님 (몇 번을 찍어도 같은 결과):
  - 카메라 비활성 설정 — `CameraUnavailableError` (camera.enabled=false 등)
  - 잘못된 requestId·페이로드 — 애초에 촬영을 시작하지 않으므로 이 모듈에 오지도 않는다
    (`src/mqtt/capture_command.py` 가 촬영 전에 걸러낸다)
  - `camera.quality.on_failure: warn` 로 통과된 밝기 이상 — 실패가 아니라 경고다
  - `retry.retry_on_quality: false` 로 꺼 둔 밝기 탈락

정책은 config `camera.retry:` 에서 읽는다 (코드 하드코딩 금지). 섹션이 아예 없으면
재시도 없이 1회만 시도하는 기존 동작을 유지한다 — 재시도는 "켜는" 기능이다.

대기는 `sleep_fn` 으로 주입할 수 있어 테스트가 실제로 기다리지 않는다.
업로드(`src/transport/uploader.py`)의 HTTP 재시도와는 완전히 별개 계층이다.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Optional

from .base import CaptureResult
from .quality import BrightnessReport, BrightnessThresholds, check_image, should_deliver

logger = logging.getLogger(__name__)


# --- 실패 코드 ----------------------------------------------------------
# 서버 회신 코드와 같은 문자열이다. capture_command 가 그대로 올려 보내므로
# 새 코드를 추가할 때는 서버 규약(docs/potner-mqtt-pi-handoff.md)을 함께 확인할 것.

CODE_CAMERA_ERROR = "CAMERA_ERROR"
CODE_CAPTURE_FAILED = "CAPTURE_FAILED"
CODE_IMAGE_SAVE_FAILED = "IMAGE_SAVE_FAILED"
CODE_TOO_DARK = "IMAGE_TOO_DARK"
CODE_TOO_BRIGHT = "IMAGE_TOO_BRIGHT"
CODE_QUALITY_UNKNOWN = "IMAGE_QUALITY_UNKNOWN"

# 밝기 판정(verdict) → 회신 코드
QUALITY_CODES = {
    "too_dark": CODE_TOO_DARK,
    "too_bright": CODE_TOO_BRIGHT,
}

# 재촬영으로 나아질 여지가 있는 실패 코드 (문서·테스트용 목록)
RETRYABLE_CODES = frozenset(
    {
        CODE_CAMERA_ERROR,
        CODE_CAPTURE_FAILED,
        CODE_IMAGE_SAVE_FAILED,
        CODE_TOO_DARK,
        CODE_TOO_BRIGHT,
        CODE_QUALITY_UNKNOWN,
    }
)


class CameraUnavailableError(RuntimeError):
    """카메라가 아예 없거나 설정으로 꺼져 있음 — 재시도 대상이 아니다.

    `RuntimeError` 를 상속하므로 기존에 `RuntimeError` 를 잡던 호출부는 그대로 동작한다.
    서버 회신 코드는 기존과 같은 CAMERA_ERROR 를 쓴다 (프로토콜 변경 없음).
    """


# --- 정책 --------------------------------------------------------------


@dataclass(frozen=True)
class RetryPolicy:
    """`camera.retry:` 설정값. 값을 바꾸려면 yaml 을 고친다."""

    enabled: bool = True
    max_attempts: int = 3            # 최초 촬영 포함 총 시도 횟수
    delay_sec: float = 1.0           # 재시도 전 대기
    backoff: bool = True             # True 면 delay_sec 를 2배씩 늘린다
    backoff_max_sec: float = 8.0     # 백오프 상한 (0 이하면 상한 없음)
    retry_on_quality: bool = True    # 밝기 검사 탈락도 재촬영 대상으로 볼지
    verify_saved_file: bool = True   # 촬영 후 파일 존재·크기(>0) 확인
    discard_failed_images: bool = False  # 재촬영으로 버려진 밝기 탈락 이미지를 삭제할지

    @property
    def attempts(self) -> int:
        """실제로 시도할 최대 횟수 (비활성이면 항상 1)."""
        if not self.enabled:
            return 1
        return max(1, int(self.max_attempts))

    def delay_for(self, attempt: int) -> float:
        """`attempt` 번째 시도가 실패한 뒤 기다릴 초 (1-based)."""
        if self.delay_sec <= 0:
            return 0.0
        delay = float(self.delay_sec)
        if self.backoff:
            delay = delay * (2 ** max(0, attempt - 1))
        if self.backoff_max_sec > 0:
            delay = min(delay, float(self.backoff_max_sec))
        return delay

    @classmethod
    def disabled(cls) -> "RetryPolicy":
        """재시도 없이 1회만 — 설정에 retry 섹션이 없을 때의 기존 동작."""
        return cls(
            enabled=False,
            max_attempts=1,
            retry_on_quality=False,
            verify_saved_file=False,
        )

    @classmethod
    def from_config(cls, config: Optional[dict[str, Any]]) -> "RetryPolicy":
        """전체 config dict / `camera` dict / `camera.retry` dict 아무거나 받는다.

        retry 섹션이 없으면 `disabled()` — 구버전 yaml 이 배포된 기기에서
        동작이 조용히 바뀌지 않게 한다.
        """
        section = _retry_section(config)
        if section is None:
            return cls.disabled()
        defaults = cls()
        return cls(
            enabled=bool(section.get("enabled", defaults.enabled)),
            max_attempts=max(1, int(section.get("max_attempts", defaults.max_attempts))),
            delay_sec=max(0.0, float(section.get("delay_sec", defaults.delay_sec))),
            backoff=bool(section.get("backoff", defaults.backoff)),
            backoff_max_sec=float(section.get("backoff_max_sec", defaults.backoff_max_sec)),
            retry_on_quality=bool(section.get("retry_on_quality", defaults.retry_on_quality)),
            verify_saved_file=bool(
                section.get("verify_saved_file", defaults.verify_saved_file)
            ),
            discard_failed_images=bool(
                section.get("discard_failed_images", defaults.discard_failed_images)
            ),
        )


_POLICY_KEYS = (
    "enabled",
    "max_attempts",
    "delay_sec",
    "backoff",
    "backoff_max_sec",
    "retry_on_quality",
    "verify_saved_file",
    "discard_failed_images",
)


def _retry_section(config: Optional[dict[str, Any]]) -> Optional[dict[str, Any]]:
    if not config:
        return None
    camera = config.get("camera")
    if isinstance(camera, dict):
        retry = camera.get("retry")
        return dict(retry) if isinstance(retry, dict) else None
    retry = config.get("retry")
    if isinstance(retry, dict):
        return dict(retry)
    if any(key in config for key in _POLICY_KEYS):
        return dict(config)
    return None


# --- 시도 결과 ---------------------------------------------------------


@dataclass(frozen=True)
class AttemptOutcome:
    """촬영 1회의 결과 (성공이든 실패든 같은 형태)."""

    attempt: int
    ok: bool
    result: Optional[CaptureResult] = None
    report: Optional[BrightnessReport] = None
    code: Optional[str] = None
    error: Optional[str] = None
    retryable: bool = False


@dataclass(frozen=True)
class CaptureAttempt:
    """재시도까지 포함한 촬영 1건의 최종 결과."""

    ok: bool
    attempts: int
    result: Optional[CaptureResult] = None
    report: Optional[BrightnessReport] = None
    code: Optional[str] = None
    error: Optional[str] = None
    retryable: bool = False
    history: tuple[AttemptOutcome, ...] = ()
    discarded: tuple[str, ...] = ()

    @property
    def retried(self) -> bool:
        return self.attempts > 1


# --- 1회 시도 ----------------------------------------------------------


def _saved_file_problem(result: CaptureResult) -> Optional[str]:
    """촬영은 성공했다는데 파일이 실제로 남았는지 확인. 문제 없으면 None."""
    if not result.path:
        return "촬영 결과에 저장 경로가 없습니다"
    path = Path(result.path)
    try:
        size = path.stat().st_size
    except OSError as exc:
        return f"저장된 이미지를 찾을 수 없습니다 ({path}): {exc}"
    if size <= 0:
        return f"저장된 이미지가 비어 있습니다 (0 bytes): {path}"
    return None


def run_attempt(
    capture_fn: Callable[[], Optional[CaptureResult]],
    *,
    attempt: int = 1,
    policy: Optional[RetryPolicy] = None,
    quality: Optional[BrightnessThresholds] = None,
    check_fn: Optional[Callable[..., BrightnessReport]] = None,
) -> AttemptOutcome:
    """촬영 1회 = 촬영 → (선택) 저장 확인 → (선택) 밝기 검사. 예외를 던지지 않는다."""
    active = policy or RetryPolicy.disabled()
    check = check_fn or check_image

    try:
        result = capture_fn()
    except CameraUnavailableError as exc:
        # 설정/하드웨어 부재 — 다시 찍어도 같다
        return AttemptOutcome(
            attempt=attempt,
            ok=False,
            code=CODE_CAMERA_ERROR,
            error=str(exc),
            retryable=False,
        )
    except OSError as exc:  # 저장 경로 권한/디스크 오류
        return AttemptOutcome(
            attempt=attempt,
            ok=False,
            code=CODE_IMAGE_SAVE_FAILED,
            error=f"{type(exc).__name__}: {exc}",
            retryable=True,
        )
    except Exception as exc:  # noqa: BLE001 — 카메라 예외는 재시도 대상
        return AttemptOutcome(
            attempt=attempt,
            ok=False,
            code=CODE_CAMERA_ERROR,
            error=str(exc),
            retryable=True,
        )

    if result is None:
        return AttemptOutcome(
            attempt=attempt,
            ok=False,
            code=CODE_CAPTURE_FAILED,
            error="촬영 함수가 결과를 돌려주지 않았습니다",
            retryable=True,
        )

    if not result.ok:
        return AttemptOutcome(
            attempt=attempt,
            ok=False,
            result=result,
            code=CODE_CAPTURE_FAILED,
            error=result.error or "capture failed",
            retryable=True,
        )

    if active.verify_saved_file:
        problem = _saved_file_problem(result)
        if problem is not None:
            return AttemptOutcome(
                attempt=attempt,
                ok=False,
                result=result,
                code=CODE_IMAGE_SAVE_FAILED,
                error=problem,
                retryable=True,
            )

    if quality is not None and quality.enabled:
        report = check(result.path, thresholds=quality)
        if not should_deliver(report):
            code = QUALITY_CODES.get(report.verdict, CODE_QUALITY_UNKNOWN)
            return AttemptOutcome(
                attempt=attempt,
                ok=False,
                result=result,
                report=report,
                code=code,
                error=report.reason,
                # 밝기는 "찍는 순간의 빛" 문제 — 다시 찍으면 달라질 수 있다
                retryable=active.retry_on_quality and report.needs_recapture,
            )
        return AttemptOutcome(attempt=attempt, ok=True, result=result, report=report)

    return AttemptOutcome(attempt=attempt, ok=True, result=result)


# --- 재시도 루프 -------------------------------------------------------


def _discard_image(outcome: AttemptOutcome) -> Optional[str]:
    """재촬영으로 버려질 밝기 탈락 이미지를 삭제. 삭제한 경로 또는 None."""
    result = outcome.result
    if outcome.report is None or result is None or not result.path:
        return None
    path = Path(result.path)
    try:
        path.unlink()
    except OSError as exc:
        logger.warning(f"실패 이미지 삭제 실패 ({path}): {exc}")
        return None
    logger.info(f"밝기 탈락 이미지 삭제 ({outcome.code}): {path}")
    return str(path)


def capture_with_retry(
    capture_fn: Callable[[], Optional[CaptureResult]],
    *,
    policy: Optional[RetryPolicy] = None,
    quality: Optional[BrightnessThresholds] = None,
    check_fn: Optional[Callable[..., BrightnessReport]] = None,
    sleep_fn: Optional[Callable[[float], None]] = None,
    on_attempt: Optional[Callable[[AttemptOutcome], None]] = None,
    request_id: Any = None,
) -> CaptureAttempt:
    """`capture_fn` 을 재시도로 감싼다. 예외를 던지지 않고 항상 `CaptureAttempt` 를 돌려준다.

    ``on_attempt`` 는 시도 1회마다 호출된다 (촬영 이벤트 로그용). 여기서 난 예외는
    촬영을 깨뜨리지 않는다 — 기록 실패로 재시도가 멈추면 안 된다.
    ``policy`` 가 None 이면 재시도 없이 1회만 시도한다.
    """
    active = policy or RetryPolicy.disabled()
    sleep = sleep_fn or time.sleep
    total = active.attempts
    label = "" if request_id is None else f" (requestId={request_id})"

    history: list[AttemptOutcome] = []
    discarded: list[str] = []

    for attempt in range(1, total + 1):
        outcome = run_attempt(
            capture_fn,
            attempt=attempt,
            policy=active,
            quality=quality,
            check_fn=check_fn,
        )
        history.append(outcome)
        if on_attempt is not None:
            try:
                on_attempt(outcome)
            except Exception as exc:  # noqa: BLE001 — 기록 실패로 촬영을 멈추지 않는다
                logger.warning(f"촬영 시도 기록 실패{label}: {exc}")

        if outcome.ok:
            if attempt == 1:
                logger.info(f"촬영 성공{label} — 1회 시도")
            else:
                logger.info(
                    f"촬영 성공{label} — {attempt}/{total}회차 시도에서 성공 "
                    f"(재시도 {attempt - 1}회)"
                )
            return CaptureAttempt(
                ok=True,
                attempts=attempt,
                result=outcome.result,
                report=outcome.report,
                history=tuple(history),
                discarded=tuple(discarded),
            )

        if not outcome.retryable:
            logger.error(
                f"촬영 실패{label} — 재시도 대상이 아닌 오류 ({outcome.code}): {outcome.error}"
            )
            break

        if attempt >= total:
            logger.error(
                f"촬영 최종 실패{label} — {attempt}회 시도 모두 실패 "
                f"(마지막 원인 {outcome.code}: {outcome.error})"
            )
            break

        # 마지막 실패분은 남긴다 (서버 회신에 path 를 실어 보내므로). 버려질 중간 실패분만 정리.
        if active.discard_failed_images:
            removed = _discard_image(outcome)
            if removed is not None:
                discarded.append(removed)

        delay = active.delay_for(attempt)
        logger.warning(
            f"촬영 재시도 {attempt}/{total}{label}: {outcome.code} — {outcome.error} "
            f"→ {delay}초 후 재촬영"
        )
        if delay > 0:
            sleep(delay)

    last = history[-1]
    return CaptureAttempt(
        ok=False,
        attempts=len(history),
        result=last.result,
        report=last.report,
        code=last.code,
        error=last.error,
        retryable=last.retryable,
        history=tuple(history),
        discarded=tuple(discarded),
    )
