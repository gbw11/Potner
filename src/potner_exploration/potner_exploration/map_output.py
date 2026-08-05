"""자동 지도 저장에 사용할 충돌 없는 출력 경로를 만듭니다."""

import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


_SAFE_NAME = re.compile(r"^[A-Za-z0-9_-]+$")


@dataclass(frozen=True)
class MapOutputPaths:
    map_base: Path
    posegraph_base: Path


def build_output_paths(
    output_dir: str | Path,
    map_name: str,
    timestamp: datetime,
) -> MapOutputPaths:
    """실행 시각이 붙은 map saver와 pose graph 기본 경로를 반환합니다."""
    if not _SAFE_NAME.fullmatch(map_name):
        raise ValueError("map_name must contain only letters, digits, '_' or '-'")

    directory = Path(output_dir).expanduser().resolve()
    directory.mkdir(parents=True, exist_ok=True)
    stem = f"{map_name}_{timestamp:%Y%m%d_%H%M%S}"
    return MapOutputPaths(
        map_base=directory / stem,
        posegraph_base=directory / f"{stem}_posegraph",
    )
