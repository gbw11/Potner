from __future__ import annotations

from typing import Any, Optional

from ..status.models import PlantStatus
from .store import EventStore


class EventDetector:
    """상태 변화가 있을 때만 이벤트를 기록. 매 샘플마다 LLM을 부르지 않음."""

    def __init__(self, store: EventStore) -> None:
        self.store = store
        self._prev: Optional[PlantStatus] = None

    def update(self, status: PlantStatus) -> list[dict[str, Any]]:
        emitted: list[dict[str, Any]] = []
        prev = self._prev
        self._prev = status

        if prev is None:
            event = {
                "timestamp": status.timestamp,
                "type": "session_start",
                "message": f"수집 시작 - {status.summary_ko}",
                "status": status.to_prompt_dict(),
            }
            self.store.append(event)
            emitted.append(event)
            return emitted

        for metric_name, curr, old in (
            ("soil", status.soil, prev.soil),
            ("temperature", status.temperature, prev.temperature),
            ("humidity", status.humidity, prev.humidity),
            ("light", status.light, prev.light),
        ):
            if curr.level == old.level:
                continue
            event = {
                "timestamp": status.timestamp,
                "type": "level_change",
                "metric": metric_name,
                "from": old.level,
                "to": curr.level,
                "message": f"{metric_name}: {old.label_ko} → {curr.label_ko}",
                "value": curr.value,
            }
            self.store.append(event)
            emitted.append(event)

        if status.needs_attention and not prev.needs_attention:
            event = {
                "timestamp": status.timestamp,
                "type": "attention",
                "message": status.summary_ko,
            }
            self.store.append(event)
            emitted.append(event)

        return emitted
