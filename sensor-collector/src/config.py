from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml


def load_dotenv_files(start: Path | None = None) -> None:
    """Load .env from project root (and cwd). Safe if python-dotenv missing."""
    try:
        from dotenv import load_dotenv
    except ImportError:
        return

    candidates: list[Path] = []
    if start is not None:
        candidates.append(Path(start))
    # Project root: main.py + cli/ (parents[1] from src/config.py)
    candidates.append(Path.cwd() / ".env")
    candidates.append(Path(__file__).resolve().parents[1] / ".env")

    seen: set[Path] = set()
    for path in candidates:
        path = path.resolve()
        if path in seen or not path.is_file():
            continue
        seen.add(path)
        load_dotenv(path, override=False)


def load_config(path: str | Path) -> dict[str, Any]:
    config_path = Path(path)
    load_dotenv_files(config_path.parent / ".env")
    with config_path.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if not isinstance(data, dict):
        raise ValueError(f"Invalid config: {config_path}")
    return data
