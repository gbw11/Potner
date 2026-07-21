from __future__ import annotations

from typing import Any, Optional

from ..collector import Collector
from ..events.detector import EventDetector
from ..events.store import EventStore
from ..llm.client import ChatMessage, LlmClient, create_llm_client
from ..llm.prompts import briefing_user_prompt, report_user_prompt
from ..llm.templates import render_briefing, render_report
from ..llm.tools import ToolHub
from ..status.models import PlantStatus
from ..status.rules import evaluate_status
from ..transport import SpringSoilPublisher, build_spring_publisher
from ..voice.console import create_stt, create_tts


class PlantService:
    """수집 → 로컬 판단 → (선택) LLM 말투 → 음성/콘솔."""

    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.collector = Collector(config)
        events_cfg = config.get("events", {}) or {}
        self.event_store = EventStore(events_cfg.get("path", "data/events.jsonl"))
        self.detector = EventDetector(self.event_store)
        self.llm: LlmClient = create_llm_client(config)
        self.tts = create_tts(config)
        self.stt = create_stt(config)
        self.publisher: SpringSoilPublisher = build_spring_publisher(config)
        self._last_status: Optional[PlantStatus] = None
        self._last_reading: Optional[dict[str, Any]] = None

    def close(self) -> None:
        self.collector.close()

    def tick(self) -> tuple[dict[str, Any], PlantStatus, list[dict[str, Any]]]:
        reading = self.collector.read_once()
        status = evaluate_status(reading, self.config.get("status_rules"))
        events = self.detector.update(status)
        self.publisher.publish(reading)
        self._last_reading = reading
        self._last_status = status
        return reading, status, events

    def push_soil(self, *, refresh: bool = True) -> dict[str, Any]:
        """토양수분만 Spring으로 전송. 응답용 dict 반환."""
        if refresh or self._last_reading is None:
            reading = self.collector.read_once()
            self._last_reading = reading
        else:
            reading = self._last_reading
        payload = self.publisher.build_payload(reading)
        result = self.publisher.publish(reading)
        return {
            "payload": payload,
            "url": self.publisher.url,
            "enabled": self.publisher.enabled,
            "ok": result.ok,
            "status_code": result.status_code,
            "message": result.message,
        }

    def current_status(self) -> PlantStatus:
        if self._last_status is None:
            _, status, _ = self.tick()
            return status
        return self._last_status

    def brief(self, *, speak: bool = True) -> str:
        _, status, _ = self.tick()
        text: Optional[str] = None
        if self.llm.available():
            text = self.llm.complete(briefing_user_prompt(status.to_prompt_dict()))
        if not text:
            text = render_briefing(status)
        if speak:
            self.tts.speak(text)
        return text

    def report(self, *, limit: int = 40, speak: bool = True) -> str:
        status = self.current_status()
        events = self.event_store.recent(limit=limit)
        text: Optional[str] = None
        if self.llm.available():
            text = self.llm.complete(
                report_user_prompt(events, status.to_prompt_dict())
            )
        if not text:
            text = render_report(events, status)
        if speak:
            self.tts.speak(text)
        return text

    def chat_once(self, user_text: str) -> str:
        status = self.current_status()
        tools = ToolHub(get_status=self.current_status, event_store=self.event_store)

        if self.llm.available():
            history = [ChatMessage(role="user", content=user_text)]
            try:
                reply = self.llm.chat_with_tools(history, tools)
                self.tts.speak(reply)
                return reply
            except Exception as exc:  # noqa: BLE001 — 폴백
                fallback = (
                    f"클라우드 대답이 안 돼서 로컬로 말할게. "
                    f"지금 나는 {status.summary_ko} ({exc})"
                )
                self.tts.speak(fallback)
                return fallback

        # 오프라인: 키워드 간단 응답 + 상태 반영
        if any(k in user_text for k in ("목마르", "물", "수분")):
            reply = f"토양 상태는 '{status.soil.label_ko}'야. {status.summary_ko}"
        elif any(k in user_text for k in ("빛", "조도", "어두")):
            reply = f"빛은 '{status.light.label_ko}' 쪽이야."
        elif any(k in user_text for k in ("온도", "춥", "덥")):
            reply = f"온도는 '{status.temperature.label_ko}'야."
        elif "어때" in user_text or "상태" in user_text:
            reply = render_briefing(status)
        else:
            reply = (
                f"응, 들었어. 나는 지금 {status.summary_ko} "
                f"OPENAI_API_KEY를 넣으면 더 자유롭게 대화할 수 있어."
            )
        self.tts.speak(reply)
        return reply

    def chat_loop(self) -> None:
        print("식물 대화 모드 - 종료: exit / quit / 종료")
        print(f"LLM={'on' if self.llm.available() else 'off(template)'}")
        while True:
            user_text = self.stt.listen("나: ")
            if not user_text:
                continue
            if user_text.lower() in {"exit", "quit", "q"} or user_text in {"종료", "나가기"}:
                print("대화 종료")
                break
            self.chat_once(user_text)
