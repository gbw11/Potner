"""자동 저장 파일 경로가 안전하고 충돌 없이 생성되는지 검증합니다."""

from datetime import datetime

import pytest

from potner_exploration.map_output import build_output_paths


def test_지도와_posegraph에_같은_실행시각을_붙인다(tmp_path):
    output = build_output_paths(
        tmp_path,
        "home",
        datetime(2026, 8, 5, 14, 30, 45),
    )

    assert output.map_base == tmp_path / "home_20260805_143045"
    assert output.posegraph_base == tmp_path / "home_20260805_143045_posegraph"


@pytest.mark.parametrize("name", ["", "../home", "floor/one", "bad name"])
def test_파일명에_경로구분자나_공백을_허용하지_않는다(tmp_path, name):
    with pytest.raises(ValueError, match="map_name"):
        build_output_paths(tmp_path, name, datetime(2026, 8, 5))
