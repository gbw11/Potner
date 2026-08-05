from __future__ import annotations

import logging
import math
from abc import ABC, abstractmethod
from dataclasses import dataclass

log = logging.getLogger(__name__)


def _as_finite(value: object, label: str) -> float:
    """숫자로 바꿀 수 있고 유한한 값만 통과. NaN/Infinity 는 여기서 막는다.

    JSON 은 NaN/Infinity 를 그대로 파싱하므로(json.loads('NaN')) 서버 페이로드가
    그대로 time.sleep 까지 흘러들어가지 않도록 경계에서 걸러야 한다.
    """
    try:
        num = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label} 는 숫자여야 합니다: {value!r}") from exc
    if not math.isfinite(num):
        raise ValueError(f"{label} 는 유한한 숫자여야 합니다: {value!r}")
    return num


@dataclass(frozen=True)
class DispensePlan:
    """실제 가동 전에 계산한 급수 계획. 하드웨어를 건드리지 않으므로 미리보기에 쓸 수 있다."""

    requested_ml: float
    run_sec: float
    expected_ml: float
    capped: bool  # max_run_sec 상한에 걸려 요청량보다 적게 나감
    floored: bool  # min_run_sec 하한 때문에 요청량보다 많이 나감


@dataclass
class DispenseResult:
    requested_ml: float
    dispensed_ml: float
    duration_sec: float
    capped: bool  # max_run_sec 안전 상한에 걸려 요청량보다 적게 급수됨
    floored: bool = False  # min_run_sec 하한에 걸려 요청량보다 많이 급수됨


class WaterPump(ABC):
    """급수 펌프 공통 인터페이스.

    급수량 제어는 유량 보정값 기반 시간 제어:
        duration = ml / flow_ml_per_sec + startup_sec
    startup_sec 은 펌프가 정격 유량에 도달하기까지의 시동 손실 보정값.
    두 값 모두 `python -m cli.water calibrate` 로 실측해 config 에 반영한다.
    호스가 비어 있으면 첫 급수가 모자라므로 사용 전 prime 으로 호스를 채울 것.

    구동 시간은 아래 두 경계로 클램프된다:
      max_run_sec — 1회 최대 가동 시간(과급수·공회전 방지). 걸리면 capped.
      min_run_sec — 펌프가 실제로 물을 뱉는 최소 가동 시간. 이보다 짧은 요청은
                    어차피 물이 안 나오면서 "요청량만큼 줬다"고 보고하게 되므로,
                    하한까지 늘려 돌리고 실제 나간 양을 보고한다. 걸리면 floored.

    서버가 계산한 필요 급수량(ml)은 dispense_ml(ml) 로 전달하면 된다.
    토양수분(%)은 ml 을 산출하지 못하므로 급수량 계산에는 쓰지 않는다 —
    급수 전 안전 게이트(src/actuators/safety.py)로만 쓴다.
    """

    def __init__(
        self,
        flow_ml_per_sec: float,
        max_run_sec: float,
        startup_sec: float = 0.0,
        min_run_sec: float = 0.0,
    ) -> None:
        flow = _as_finite(flow_ml_per_sec, "flow_ml_per_sec")
        max_run = _as_finite(max_run_sec, "max_run_sec")
        startup = _as_finite(startup_sec, "startup_sec")
        min_run = _as_finite(min_run_sec, "min_run_sec")
        if flow <= 0:
            raise ValueError(f"flow_ml_per_sec 는 0보다 커야 합니다: {flow}")
        if max_run <= 0:
            raise ValueError(f"max_run_sec 는 0보다 커야 합니다: {max_run}")
        if startup < 0:
            raise ValueError(f"startup_sec 는 0 이상이어야 합니다: {startup}")
        if min_run < 0:
            raise ValueError(f"min_run_sec 는 0 이상이어야 합니다: {min_run}")
        if min_run > max_run:
            raise ValueError(
                f"min_run_sec({min_run}) 가 max_run_sec({max_run}) 보다 큽니다. config 를 확인하세요."
            )
        self.flow_ml_per_sec = flow
        self.max_run_sec = max_run
        self.startup_sec = startup
        self.min_run_sec = min_run

    def duration_for_ml(self, ml: float) -> float:
        """요청량(ml)에 필요한 이론 가동 시간. 상·하한은 적용하지 않는다."""
        requested = _as_finite(ml, "급수량(ml)")
        if requested < 0:
            raise ValueError(f"급수량은 0 이상이어야 합니다: {requested}")
        return requested / self.flow_ml_per_sec + self.startup_sec

    def ml_for_duration(self, seconds: float) -> float:
        """가동 시간에서 역산한 급수량. 시동 손실 구간에서는 0."""
        run_sec = _as_finite(seconds, "가동 시간(초)")
        return max(0.0, run_sec - self.startup_sec) * self.flow_ml_per_sec

    def plan_dispense(self, ml: float) -> DispensePlan:
        """급수 계획만 계산 (펌프를 돌리지 않음). CLI 미리보기·테스트용."""
        requested = _as_finite(ml, "급수량(ml)")
        if requested <= 0:
            raise ValueError(f"급수량은 0보다 커야 합니다: {requested}")

        run_sec = self.duration_for_ml(requested)
        floored = run_sec < self.min_run_sec
        if floored:
            run_sec = self.min_run_sec
        capped = run_sec > self.max_run_sec
        if capped:
            run_sec = self.max_run_sec
            floored = False  # 상한까지 늘어난 시점에 하한 얘기는 의미가 없다

        # 실제 가동값과 보고값이 어긋나지 않도록 한 번만 반올림해 둘 다에 쓴다
        run_sec = round(run_sec, 2)
        return DispensePlan(
            requested_ml=requested,
            run_sec=run_sec,
            expected_ml=round(self.ml_for_duration(run_sec), 1),
            capped=capped,
            floored=floored,
        )

    def dispense_ml(self, ml: float) -> DispenseResult:
        """ml 만큼 급수. max_run_sec 상한 / min_run_sec 하한이 적용된다."""
        plan = self.plan_dispense(ml)
        if plan.capped:
            log.warning(
                f"급수량 상한: 요청 {plan.requested_ml:.0f} ml 은 max_run_sec="
                f"{self.max_run_sec:.1f}s 를 넘어 약 {plan.expected_ml:.0f} ml 만 급수합니다."
            )
        if plan.floored:
            log.warning(
                f"급수량 하한: 요청 {plan.requested_ml:.1f} ml 은 min_run_sec="
                f"{self.min_run_sec:.2f}s 보다 짧아 약 {plan.expected_ml:.1f} ml 이 나갑니다."
            )
        log.info(f"급수 시작: 요청 {plan.requested_ml:.0f} ml → {plan.run_sec:.2f}s 가동")
        try:
            self._run(plan.run_sec)
        finally:
            self.stop()
        log.info(f"급수 완료: 약 {plan.expected_ml:.0f} ml / {plan.run_sec:.2f}s")
        return DispenseResult(
            requested_ml=plan.requested_ml,
            dispensed_ml=plan.expected_ml,
            duration_sec=plan.run_sec,
            capped=plan.capped,
            floored=plan.floored,
        )

    def run_for(self, seconds: float) -> None:
        """시간 지정 가동 (보정/테스트용). max_run_sec 상한 적용."""
        run_sec = _as_finite(seconds, "가동 시간(초)")
        if run_sec <= 0:
            raise ValueError(f"가동 시간은 0보다 커야 합니다: {run_sec}")
        if run_sec > self.max_run_sec:
            log.warning(
                f"가동 시간 {run_sec:.2f}s 가 max_run_sec={self.max_run_sec:.1f}s 를 넘어 상한으로 줄입니다."
            )
            run_sec = self.max_run_sec
        try:
            self._run(run_sec)
        finally:
            self.stop()

    @abstractmethod
    def _run(self, seconds: float) -> None:
        """seconds 동안 펌프 가동 (blocking). stop() 은 호출부가 보장."""
        raise NotImplementedError

    @abstractmethod
    def stop(self) -> None:
        raise NotImplementedError

    def close(self) -> None:  # noqa: B027 — GPIO 구현체만 자원 해제 필요
        pass


class Fan(ABC):
    """송풍 팬 공통 인터페이스. 속도는 PWM duty 0~100%.

    소프트웨어 PWM 은 프로세스가 살아 있는 동안만 유지되므로,
    CLI 등에서 켠 팬은 프로세스 종료 시 함께 멈춘다.
    """

    def set_speed(self, speed_pct: float) -> float:
        """속도 설정 (0=정지, 100=최대). 클램프된 실제 적용값 반환."""
        clamped = min(max(float(speed_pct), 0.0), 100.0)
        self._apply_speed(clamped)
        return clamped

    def on(self, speed_pct: float = 100.0) -> float:
        return self.set_speed(speed_pct)

    def off(self) -> None:
        self.set_speed(0.0)

    def run_for(self, seconds: float, speed_pct: float = 100.0) -> None:
        """seconds 동안 speed_pct 로 가동 후 정지 (blocking)."""
        if seconds <= 0:
            raise ValueError(f"가동 시간은 0보다 커야 합니다: {seconds}")
        import time

        try:
            self.set_speed(speed_pct)
            time.sleep(float(seconds))
        finally:
            self.off()

    @abstractmethod
    def _apply_speed(self, speed_pct: float) -> None:
        """클램프된 0~100 값을 하드웨어에 반영."""
        raise NotImplementedError

    def close(self) -> None:  # noqa: B027 — GPIO 구현체만 자원 해제 필요
        pass
