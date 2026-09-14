from __future__ import annotations

from typing import Protocol


class TextToSpeech(Protocol):
    def speak(self, text: str) -> None: ...


class SpeechToText(Protocol):
    def listen(self, prompt: str = "") -> str: ...
