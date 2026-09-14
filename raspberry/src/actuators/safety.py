"""과급수 방지 게이트.

급수량 자체는 ml→시간 환산(`base.py`)이 결정한다. 이 모듈은 그 앞단에서
"지금 이만큼 줘도 되는가"만 판정한다. 네 가지 축으로 막는다:

  1회 상한   max_ml_per_dose  — 서버가 잘못된 큰 값을 보내도 한 번에 이만큼까지만
  재급수 간격 min_interval_sec — 직전 급수 직후 중복 명령 차단 (재전송·수동 연타)
  누적 상한   daily_max_ml     — 최근 24시간 총량. 반복 명령으로 화분이 잠기는 것 방지
  토양수분   soil_wet_pct     — 이미 젖어 있으면 급수 스킵

토양수분 센서는 %만 주고 ml 을 주지 못하므로 급수량 계산에는 쓰지 않는다.
여기서 "젖었으면 멈춘다"는 안전 게이트로만 쓰고, 센서가 없거나 읽기에
실패하면 게이트는 통과시킨다 (fail-open) — 센서 고장으로 급수가 영영 멈추는 쪽이
더 위험하기 때문. 대신 나머지 세 축(양·간격·누적)은 센서 없이도 항상 동작한다.

누적량은 프로세스가 재시작돼도 유지돼야 하므로(systemd 재기동, CLI 는 매번 새 프로세스)
state_path 에 JSON 으로 남긴다. 파일 IO 가 실패하면 메모리 이력만으로 계속 동작한다.
"""

from __future__ import annotations

import json
import logging
import math
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Optional

log = logging.getLogger(__name__)

WINDOW_SEC = 24 * 60 * 60  # daily_max_ml 을 판정하는 롤링 윈도우
MIN_USEFUL_ML = 1.0  # 남은 허용량이 이보다 적으면 급수하지 않고 거절


@dataclass(frozen=True)
class SafetyLimits:
    """None / 0 이하는 "그 축은 검사하지 않음"을 뜻한다."""

    max_ml_per_dose: Optional[float] = None
    min_interval_sec: float = 0.0
    daily_max_ml: Optional[float] = None
    soil_wet_pct: Optional[float] = None


@dataclass(frozen=True)
class GuardDecision:
    """급수 허용 판정 결과.

    allowed=True 라도 ml 이 requested_ml 보다 작을 수 있다 (상한에 맞춰 감액).
    """

    allowed: bool
    ml: float
    requested_ml: float
    code: str  # OK | LIMITED | INVALID | SOIL_WET | TOO_SOON | DAILY_LIMIT
    reason: str = ""

    @property
    def limited(self) -> bool:
        return self.allowed and self.ml < self.requested_ml


def _positive_or_none(value: Any) -> Optional[float]:
    """config 값을 양수 float 로. None/빈값/0 이하/비정상 값은 None(=검사 안 함)."""
    if value is None or value == "":
        return None
    try:
        num = float(value)
    except (TypeError, ValueError):
        log.warning(f"pump.safety 설정값이 숫자가 아닙니다 (무시): {value!r}")
        return None
    if not math.isfinite(num) or num <= 0:
        return None
    return num


class WateringGuard:
    """급수 전 안전 판정 + 급수 이력 누적.

    check() 로 판정하고, 실제로 급수한 뒤 record() 로 실급수량을 적는다.
    (요청량이 아니라 실제 나간 양을 적어야 누적 상한이 현실과 맞는다.)
    """

    def __init__(
        self,
        limits: SafetyLimits,
        *,
        soil_provider: Optional[Callable[[], Optional[float]]] = None,
        state_path: Optional[str | Path] = None,
        time_fn: Callable[[], float] = time.time,
    ) -> None:
        self.limits = limits
        self.soil_provider = soil_provider
        self.state_path = Path(state_path) if state_path else None
        self._time_fn = time_fn
        self._lock = threading.Lock()
        self._doses: list[tuple[float, float]] = []  # (epoch_sec, ml)
        self._load_state()

    # --- 판정 ---

    def check(self, ml: float) -> GuardDecision:
        try:
            requested = float(ml)
        except (TypeError, ValueError):
            return GuardDecision(False, 0.0, 0.0, "INVALID", f"급수량이 숫자가 아닙니다: {ml!r}")
        if not math.isfinite(requested) or requested <= 0:
            return GuardDecision(
                False, 0.0, requested if math.isfinite(requested) else 0.0, "INVALID",
                f"급수량은 0보다 큰 유한한 값이어야 합니다: {ml!r}",
            )

        soil = self._read_soil()
        wet_pct = self.limits.soil_wet_pct
        if soil is not None and wet_pct is not None and soil >= wet_pct:
            return GuardDecision(
                False, 0.0, requested, "SOIL_WET",
                f"토양수분 {soil:.0f}% 가 기준 {wet_pct:.0f}% 이상이라 급수를 건너뜁니다.",
            )

        now = self._time_fn()
        with self._lock:
            self._prune(now)
            interval = self.limits.min_interval_sec
            if interval and self._doses:
                elapsed = now - self._doses[-1][0]
                if elapsed < interval:
                    return GuardDecision(
                        False, 0.0, requested, "TOO_SOON",
                        f"직전 급수 후 {elapsed:.0f}초밖에 지나지 않았습니다 "
                        f"(최소 간격 {interval:.0f}초).",
                    )
            used = sum(dose_ml for _, dose_ml in self._doses)

        allowed_ml = requested
        reasons: list[str] = []

        per_dose = self.limits.max_ml_per_dose
        if per_dose is not None and allowed_ml > per_dose:
            reasons.append(f"1회 상한 {per_dose:.0f} ml")
            allowed_ml = per_dose

        daily = self.limits.daily_max_ml
        if daily is not None:
            remaining = daily - used
            if remaining < MIN_USEFUL_ML:
                return GuardDecision(
                    False, 0.0, requested, "DAILY_LIMIT",
                    f"최근 24시간 급수량 {used:.0f} ml 이 상한 {daily:.0f} ml 에 도달했습니다.",
                )
            if allowed_ml > remaining:
                reasons.append(f"24시간 잔여 {remaining:.0f} ml")
                allowed_ml = remaining

        allowed_ml = round(allowed_ml, 1)
        if reasons:
            joined = ", ".join(reasons)
            return GuardDecision(
                True, allowed_ml, requested, "LIMITED",
                f"요청 {requested:.0f} ml 을 {allowed_ml:.0f} ml 로 줄였습니다 ({joined}).",
            )
        return GuardDecision(True, allowed_ml, requested, "OK")

    def record(self, ml: float) -> None:
        """실제 급수한 양을 이력에 남긴다 (요청량이 아니라 실급수량)."""
        try:
            amount = float(ml)
        except (TypeError, ValueError):
            return
        if not math.isfinite(amount) or amount <= 0:
            return
        now = self._time_fn()
        with self._lock:
            self._doses.append((now, amount))
            self._prune(now)
            self._save_state()

    def used_ml(self) -> float:
        """최근 24시간 누적 급수량."""
        now = self._time_fn()
        with self._lock:
            self._prune(now)
            return round(sum(dose_ml for _, dose_ml in self._doses), 1)

    # --- 내부 ---

    def _read_soil(self) -> Optional[float]:
        if self.soil_provider is None:
            return None
        try:
            value = self.soil_provider()
        except Exception as exc:  # noqa: BLE001 — 센서 고장이 급수를 영구히 막으면 안 된다
            log.warning(f"토양수분 확인 실패 (게이트 통과 처리): {exc}")
            return None
        if value is None:
            return None
        try:
            pct = float(value)
        except (TypeError, ValueError):
            log.warning(f"토양수분 값이 숫자가 아닙니다 (게이트 통과 처리): {value!r}")
            return None
        if not math.isfinite(pct):
            return None
        return pct

    def _prune(self, now: float) -> None:
        cutoff = now - WINDOW_SEC
        # 시계가 뒤로 튀면(NTP 보정) 미래 타임스탬프가 남아 영원히 막을 수 있어 함께 정리
        self._doses = [(ts, ml) for ts, ml in self._doses if cutoff <= ts <= now]

    def _load_state(self) -> None:
        if self.state_path is None or not self.state_path.exists():
            return
        try:
            raw = json.loads(self.state_path.read_text(encoding="utf-8"))
            doses = raw.get("doses") or []
            loaded: list[tuple[float, float]] = []
            for item in doses:
                ts, ml = float(item[0]), float(item[1])
                if math.isfinite(ts) and math.isfinite(ml) and ml > 0:
                    loaded.append((ts, ml))
            self._doses = sorted(loaded)
            self._prune(self._time_fn())
        except Exception as exc:  # noqa: BLE001 — 상태 파일이 깨져도 급수는 되어야 한다
            log.warning(f"급수 이력 파일을 읽을 수 없습니다 ({self.state_path}): {exc}")
            self._doses = []

    def _save_state(self) -> None:
        if self.state_path is None:
            return
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            payload = {"doses": [[ts, ml] for ts, ml in self._doses]}
            self.state_path.write_text(
                json.dumps(payload, ensure_ascii=False), encoding="utf-8"
            )
        except OSError as exc:
            log.warning(f"급수 이력을 저장할 수 없습니다 ({self.state_path}): {exc}")


def build_guard(
    config: dict[str, Any],
    *,
    soil_provider: Optional[Callable[[], Optional[float]]] = None,
    time_fn: Callable[[], float] = time.time,
) -> Optional[WateringGuard]:
    """config 의 pump.safety 섹션에서 가드 생성. 비활성이면 None."""
    safety_cfg = (config.get("pump") or {}).get("safety") or {}
    if not safety_cfg.get("enabled", False):
        return None

    limits = SafetyLimits(
        max_ml_per_dose=_positive_or_none(safety_cfg.get("max_ml_per_dose")),
        min_interval_sec=_positive_or_none(safety_cfg.get("min_interval_sec")) or 0.0,
        daily_max_ml=_positive_or_none(safety_cfg.get("daily_max_ml")),
        soil_wet_pct=_positive_or_none(safety_cfg.get("soil_wet_pct")),
    )
    state_path = safety_cfg.get("state_path")
    return WateringGuard(
        limits,
        soil_provider=soil_provider,
        state_path=str(state_path) if state_path else None,
        time_fn=time_fn,
    )
