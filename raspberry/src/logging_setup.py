"""애플리케이션 로깅 기반.

각 모듈은 `logging.getLogger(__name__)` 만 쓰고, 핸들러 구성은 여기서 한 번만 한다.
엔트리포인트(main.py, cli/*)가 시작 시 `setup_logging(config)` 을 호출한다.

config 예시:
    logging:
      level: INFO          # DEBUG | INFO | WARNING | ERROR
      console: true        # 콘솔(stderr) 출력 여부
      path: data/app.log   # null 이면 파일 로그 비활성
      max_bytes: 1048576   # 회전 기준 (1MB)
      backup_count: 5      # app.log.1 ~ app.log.5

설정이 없으면 INFO + 콘솔만으로 동작한다 (파일 로그 없음).
"""

from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any

LOG_FORMAT = "%(asctime)s %(levelname)-7s %(name)s: %(message)s"
DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

# 우리가 붙인 핸들러만 식별해서 재설정 시 교체한다 (pytest caplog 등 외부 핸들러는 보존)
_MARKER = "_potner_handler"

_DEFAULTS: dict[str, Any] = {
    "level": "INFO",
    "console": True,
    "path": None,
    "max_bytes": 1024 * 1024,
    "backup_count": 5,
}


def _resolve_level(value: Any) -> int:
    """'INFO' / 20 / 잘못된 값 → 로그 레벨 정수. 알 수 없으면 INFO."""
    if isinstance(value, int):
        return value
    level = logging.getLevelName(str(value).upper())
    return level if isinstance(level, int) else logging.INFO


def _clear_our_handlers(logger: logging.Logger) -> None:
    for handler in list(logger.handlers):
        if getattr(handler, _MARKER, False):
            logger.removeHandler(handler)
            handler.close()


def setup_logging(config: dict[str, Any] | None = None) -> logging.Logger:
    """루트 로거에 콘솔/회전 파일 핸들러를 구성한다.

    두 번 호출해도 핸들러가 중복되지 않는다 (이전 구성분만 교체).
    파일 경로를 만들 수 없으면 콘솔 로깅은 유지한 채 경고만 남긴다.
    """
    cfg = {**_DEFAULTS, **((config or {}).get("logging") or {})}
    level = _resolve_level(cfg["level"])

    root = logging.getLogger()
    root.setLevel(level)
    _clear_our_handlers(root)

    formatter = logging.Formatter(LOG_FORMAT, datefmt=DATE_FORMAT)

    if cfg["console"]:
        console = logging.StreamHandler()
        console.setFormatter(formatter)
        console.setLevel(level)
        setattr(console, _MARKER, True)
        root.addHandler(console)

    path = cfg["path"]
    if path:
        log_path = Path(path)
        try:
            log_path.parent.mkdir(parents=True, exist_ok=True)
            file_handler = RotatingFileHandler(
                log_path,
                maxBytes=int(cfg["max_bytes"]),
                backupCount=int(cfg["backup_count"]),
                encoding="utf-8",
            )
            file_handler.setFormatter(formatter)
            file_handler.setLevel(level)
            setattr(file_handler, _MARKER, True)
            root.addHandler(file_handler)
        except OSError as exc:
            # 파일 로그가 안 되더라도 프로그램은 계속 떠야 한다
            root.warning(f"로그 파일을 열 수 없습니다 ({log_path}): {exc}")

    return root
