"""LLM/입력이 끊겨도 대화 계층이 죽지 않는지 — 루프 2 시뮬레이션에서 발견된 두 버그의 회귀 테스트.

- brief/report: GMS 가 끊기면 traceback 대신 템플릿 문장으로 폴백해야 한다
  (chat_once 는 원래 폴백을 갖고 있었는데 brief/report 만 무방비였다).
- chat_loop: EOF(Ctrl+D·파이프 입력 끝)/Ctrl+C 는 "종료" 를 친 것과 같게 다뤄야 한다.
"""

from __future__ import annotations

import urllib.error

import pytest

from src.dialogue.service import PlantService


def _mock_config(tmp_path) -> dict:
    """LLM 켜짐 + mock 센서. 실제 네트워크로는 나가지 않는다 (아래에서 llm 을 갈아끼움)."""
    return {
        "platform": "mock",
        "storage": {"path": str(tmp_path / "readings.csv")},
        "events": {"path": str(tmp_path / "events.jsonl")},
        "sensors": {
            "climate": {"enabled": True},
            "light": {"enabled": True},
            "soil": {"enabled": True},
        },
        "camera": {"enabled": False},
        "backend": {"enabled": False},
        "llm": {"enabled": False},  # create_llm_client 가 네트워크를 안 잡도록
    }


class _DeadLlm:
    """available() 은 True 인데 호출하면 네트워크 예외를 던지는 LLM (GMS 끊김 재현)."""

    def __init__(self) -> None:
        self.calls = 0

    def available(self) -> bool:
        return True

    def complete(self, _prompt: str, **_kwargs) -> str:
        self.calls += 1
        raise urllib.error.URLError("connection refused")


@pytest.fixture()
def service(tmp_path):
    svc = PlantService(_mock_config(tmp_path))
    try:
        yield svc
    finally:
        svc.close()


def test_brief_falls_back_to_template_when_llm_dies(service, caplog):
    """LLM 이 끊겨도 brief 는 템플릿 문장을 돌려주고 예외를 밖으로 내지 않는다."""
    service.llm = _DeadLlm()

    text = service.brief(speak=False)

    assert text  # 빈 문자열이 아니라 실제 템플릿 문장
    assert service.llm.calls == 1  # LLM 을 시도는 했다
    assert "폴백" in caplog.text  # 조용히 삼키지 않고 warning 을 남긴다


def test_report_falls_back_to_template_when_llm_dies(service):
    """report 도 같은 폴백 정책을 따른다."""
    service.llm = _DeadLlm()

    text = service.report(limit=5, speak=False)

    assert text
    assert service.llm.calls == 1


def test_llm_text_returns_none_without_calling_when_llm_unavailable(service):
    """available() 이 False 면 호출 자체를 하지 않는다 (기존 동작 유지)."""

    class _OffLlm(_DeadLlm):
        def available(self) -> bool:
            return False

    service.llm = _OffLlm()

    assert service._llm_text("prompt") is None
    assert service.llm.calls == 0


def _run_chat_loop_with_stdin(service, monkeypatch, exc: BaseException) -> None:
    """stt.listen 이 주어진 예외를 던지도록 바꿔 chat_loop 를 돌린다."""

    class _RaisingStt:
        def listen(self, _prompt: str) -> str:
            raise exc

    service.stt = _RaisingStt()
    service.chat_loop()  # 예외가 새어나오면 테스트 실패


def test_chat_loop_exits_cleanly_on_eof(service, monkeypatch, capsys):
    """파이프 입력 끝/Ctrl+D 는 EOFError traceback 이 아니라 정상 종료여야 한다."""
    _run_chat_loop_with_stdin(service, monkeypatch, EOFError())

    assert "대화 종료" in capsys.readouterr().out


def test_chat_loop_exits_cleanly_on_ctrl_c(service, monkeypatch, capsys):
    """Ctrl+C 도 같은 경로로 조용히 종료한다."""
    _run_chat_loop_with_stdin(service, monkeypatch, KeyboardInterrupt())

    assert "대화 종료" in capsys.readouterr().out
