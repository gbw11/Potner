"""수위 센서(플로트 스위치) 드라이버·CLI 테스트 — 하드웨어 불필요.

가짜 gpiozero 모듈을 sys.modules 에 주입해서 FloatSwitchSensor 를 검증한다.
(실제 gpiozero/GPIO 는 Pi 에만 있음 — 테스트는 어디서든 통과해야 한다.)
"""

from __future__ import annotations

import json
import sys
import types

import pytest

from src.sensors.water_level import (
    DEFAULT_BOUNCE_SEC,
    DEFAULT_PIN,
    FloatSwitchSensor,
    MockFloatSwitchSensor,
    WaterLevelReading,
)


class FakeButton:
    """gpiozero.Button 대역. 생성 인자를 기록하고 is_pressed 를 조작할 수 있다.

    seq 에 값을 넣으면 읽을 때마다 순서대로 소비한다 (read_stable 다수결 검증용).
    """

    instances: list["FakeButton"] = []

    def __init__(self, pin, pull_up=None, bounce_time=None):
        self.pin = pin
        self.pull_up = pull_up
        self.bounce_time = bounce_time
        self._pressed = False
        self.seq: list[bool] = []
        self.closed = False
        FakeButton.instances.append(self)

    @property
    def is_pressed(self) -> bool:
        if self.seq:
            return self.seq.pop(0)
        return self._pressed

    @is_pressed.setter
    def is_pressed(self, value: bool) -> None:
        self._pressed = value

    def close(self):
        self.closed = True


@pytest.fixture
def fake_gpiozero(monkeypatch):
    FakeButton.instances = []
    module = types.ModuleType("gpiozero")
    module.Button = FakeButton
    monkeypatch.setitem(sys.modules, "gpiozero", module)
    return module


# ---------------------------------------------------------------- FloatSwitch


def test_button_wired_as_pullup_input_on_requested_pin(fake_gpiozero):
    sensor = FloatSwitchSensor(27)
    button = FakeButton.instances[0]
    assert button.pin == 27
    assert button.pull_up is True  # 접점 닫힘 = GND 로 LOW = is_pressed
    assert button.bounce_time == DEFAULT_BOUNCE_SEC
    assert sensor.pin == 27


def test_no_type_semantics_without_invert(fake_gpiozero):
    """NO 타입 원래 해석 (invert=False 명시): 닫힘 = 물 있음."""
    sensor = FloatSwitchSensor(27, invert=False)
    button = FakeButton.instances[0]

    button.is_pressed = True  # 접점 닫힘
    reading = sensor.read()
    assert reading.raw_closed is True
    assert reading.water_present is True

    button.is_pressed = False  # 접점 열림
    reading = sensor.read()
    assert reading.raw_closed is False
    assert reading.water_present is False


def test_default_invert_matches_field_measurement(fake_gpiozero):
    """실물 확인(2026-08-03): 이 장착에서 접점 닫힘 = 물 없음 — 기본값이 이를 반영해야 한다."""
    sensor = FloatSwitchSensor(27)  # invert 기본값
    button = FakeButton.instances[0]

    button.is_pressed = True  # 접점 닫힘 = 뜨개 내려감
    assert sensor.read().water_present is False

    button.is_pressed = False  # 접점 열림 = 뜨개 올라감
    assert sensor.read().water_present is True


def test_invert_flips_interpretation_but_not_raw(fake_gpiozero):
    sensor = FloatSwitchSensor(27, invert=True)
    button = FakeButton.instances[0]

    button.is_pressed = True
    reading = sensor.read()
    assert reading.raw_closed is True  # 원시값은 그대로
    assert reading.water_present is False  # 해석만 반전

    button.is_pressed = False
    assert sensor.read().water_present is True


def test_read_stable_majority_vote_absorbs_slosh(fake_gpiozero):
    """뜨개 출렁임으로 표가 갈려도 과반으로 판정한다."""
    sensor = FloatSwitchSensor(27, invert=False)
    button = FakeButton.instances[0]
    sleeps: list[float] = []

    button.seq = [True, False, True, False, True]  # 출렁임: 닫힘 3표 / 열림 2표
    reading = sensor.read_stable(samples=5, sample_interval_sec=0.06, sleep_fn=sleeps.append)
    assert reading.raw_closed is True
    assert reading.water_present is True
    assert sleeps == [0.06] * 4  # 표 사이에만 대기

    button.seq = [False, False, True, False, True]  # 열림 3표 / 닫힘 2표
    assert sensor.read_stable(samples=5, sleep_fn=sleeps.append).raw_closed is False


def test_read_stable_single_sample_never_sleeps(fake_gpiozero):
    sensor = FloatSwitchSensor(27, invert=False)
    FakeButton.instances[0].is_pressed = True
    sleeps: list[float] = []
    reading = sensor.read_stable(samples=1, sleep_fn=sleeps.append)
    assert reading.raw_closed is True
    assert sleeps == []


def test_close_releases_gpio(fake_gpiozero):
    sensor = FloatSwitchSensor(27)
    sensor.close()
    assert FakeButton.instances[0].closed is True


def test_missing_gpiozero_raises_helpful_import_error(monkeypatch):
    monkeypatch.setitem(sys.modules, "gpiozero", None)  # import 시 ImportError 유발
    with pytest.raises(ImportError, match="gpiozero"):
        FloatSwitchSensor(27)


def test_default_pin_is_physical_13(fake_gpiozero):
    FloatSwitchSensor()
    assert FakeButton.instances[0].pin == DEFAULT_PIN == 27


# ------------------------------------------------------------------- 드라이버 mock


def test_mock_sensor_follows_sequence_then_holds_last():
    sensor = MockFloatSwitchSensor(values=[True, False])
    assert sensor.read().raw_closed is True
    assert sensor.read().raw_closed is False
    assert sensor.read().raw_closed is False  # 마지막 값 유지


def test_mock_sensor_toggle_alternates():
    sensor = MockFloatSwitchSensor(toggle=True)
    first = sensor.read().raw_closed
    second = sensor.read().raw_closed
    assert first != second


def test_mock_sensor_respects_invert():
    sensor = MockFloatSwitchSensor(values=[True], invert=True)
    reading = sensor.read()
    assert reading.raw_closed is True
    assert reading.water_present is False


def test_reading_to_dict_schema():
    reading = WaterLevelReading(raw_closed=True, water_present=False)
    assert reading.to_dict() == {"raw_closed": True, "water_present": False}


# ------------------------------------------------------------------------ CLI


def test_cli_read_mock_prints_json_with_expected_keys(capsys):
    from cli.water_level import main

    assert main(["read", "--mock"]) == 0
    payload = json.loads(capsys.readouterr().out)
    assert set(payload) == {"pin", "timestamp", "raw_closed", "water_present"}
    assert payload["pin"] == -1  # mock 표시


def test_cli_read_uses_float_switch_on_requested_pin(fake_gpiozero, capsys):
    from cli.water_level import main

    assert main(["read", "--pin", "22"]) == 0
    button = FakeButton.instances[0]
    assert button.pin == 22
    assert button.closed is True  # read 후 GPIO 해제
    payload = json.loads(capsys.readouterr().out)
    assert payload["pin"] == 22
    assert payload["raw_closed"] is False  # FakeButton 기본값(열림)
    assert payload["water_present"] is True  # 열림 + 기본 invert(실물 확인값) = 물 있음


def test_cli_no_invert_flag_restores_no_semantics(fake_gpiozero, capsys):
    from cli.water_level import main

    main(["read", "--no-invert"])
    payload = json.loads(capsys.readouterr().out)
    assert payload["raw_closed"] is False
    assert payload["water_present"] is False  # 열림 + NO 원래 해석 = 물 없음


def test_cli_read_without_gpiozero_exits_cleanly_with_mock_hint(monkeypatch, capsys):
    """PC 에서 --mock 없이 실행하면 traceback 대신 안내 + 종료코드 2 가 나와야 한다."""
    from cli.water_level import main

    monkeypatch.setitem(sys.modules, "gpiozero", None)  # import 시 ImportError 유발
    assert main(["read"]) == 2
    err = capsys.readouterr().err
    assert "gpiozero" in err
    assert "--mock" in err


def test_cli_read_uses_read_stable_majority(fake_gpiozero, capsys):
    """read 서브커맨드는 단발 read 가 아니라 read_stable 다수결로 판정해야 한다.

    첫 표(열림)와 다수결(닫힘)이 다른 시퀀스를 넣어, 단발 read 였다면
    False 가 나올 상황에서 True 가 나오는지로 구분한다.
    """
    from cli.water_level import main

    original_button = fake_gpiozero.Button

    def button_with_slosh(*args, **kwargs):
        button = original_button(*args, **kwargs)
        button.seq = [False, True, True, False, True]  # 닫힘 3표 / 열림 2표, 첫 표는 열림
        return button

    fake_gpiozero.Button = button_with_slosh

    # read_stable 기본 표 간 대기(0.06s x 4 = 0.24s)는 실제로 흘러가지만 무시할 수준
    assert main(["read", "--no-invert"]) == 0
    button = FakeButton.instances[0]
    assert button.seq == []  # 5표를 전부 소비했다 = 다수결 경로
    payload = json.loads(capsys.readouterr().out)
    assert payload["raw_closed"] is True  # 단발 read 였다면 첫 표 False
    assert payload["water_present"] is True
