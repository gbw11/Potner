from __future__ import annotations

from typing import Any


class ConsoleTTS:
    """실제 스피커 대신 콘솔에 출력. 나중에 ElevenLabs/로컬 TTS로 교체."""

    def speak(self, text: str) -> None:
        try:
            print(f"[식물] {text}")
        except UnicodeEncodeError:
            print("[식물]", text.encode("utf-8", errors="replace").decode("utf-8", errors="replace"))


class ConsoleSTT:
    """마이크 대신 키보드 입력. 나중에 Whisper.cpp 등으로 교체."""

    def listen(self, prompt: str = "나: ") -> str:
        return input(prompt).strip()


def create_tts(config: dict[str, Any]) -> ConsoleTTS:
    # voice.tts: console | none  (향후 api 등 추가)
    return ConsoleTTS()


def create_stt(config: dict[str, Any]) -> ConsoleSTT:
    return ConsoleSTT()
