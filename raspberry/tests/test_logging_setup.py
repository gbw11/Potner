import logging

from src.logging_setup import setup_logging


def _our_handlers(logger):
    return [h for h in logger.handlers if getattr(h, "_potner_handler", False)]


def _teardown():
    root = logging.getLogger()
    for handler in _our_handlers(root):
        root.removeHandler(handler)
        handler.close()


def test_setup_logging_defaults_to_console_only():
    try:
        root = setup_logging({})
        handlers = _our_handlers(root)
        assert len(handlers) == 1
        assert isinstance(handlers[0], logging.StreamHandler)
        assert root.level == logging.INFO
    finally:
        _teardown()


def test_setup_logging_writes_rotating_file(tmp_path):
    log_path = tmp_path / "logs" / "app.log"
    try:
        setup_logging({"logging": {"path": str(log_path), "level": "DEBUG"}})
        logging.getLogger("test.capture").info("촬영 완료: frame_1.jpg")
        for handler in _our_handlers(logging.getLogger()):
            handler.flush()
        assert log_path.exists()
        content = log_path.read_text(encoding="utf-8")
        assert "촬영 완료: frame_1.jpg" in content
        assert "INFO" in content
    finally:
        _teardown()


def test_setup_logging_records_levels_distinctly(tmp_path):
    log_path = tmp_path / "app.log"
    try:
        setup_logging({"logging": {"path": str(log_path), "console": False}})
        log = logging.getLogger("test.levels")
        log.info("정상")
        log.warning("주의")
        log.error("실패")
        for handler in _our_handlers(logging.getLogger()):
            handler.flush()
        content = log_path.read_text(encoding="utf-8")
        assert "INFO" in content and "정상" in content
        assert "WARNING" in content and "주의" in content
        assert "ERROR" in content and "실패" in content
    finally:
        _teardown()


def test_setup_logging_is_idempotent(tmp_path):
    cfg = {"logging": {"path": str(tmp_path / "app.log")}}
    try:
        setup_logging(cfg)
        setup_logging(cfg)
        # 두 번 호출해도 콘솔 1 + 파일 1 만 남아야 한다 (중복 기록 방지)
        assert len(_our_handlers(logging.getLogger())) == 2
    finally:
        _teardown()


def test_setup_logging_unknown_level_falls_back_to_info():
    try:
        root = setup_logging({"logging": {"level": "VERBOSE"}})
        assert root.level == logging.INFO
    finally:
        _teardown()
