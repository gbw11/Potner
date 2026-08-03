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
    """gpiozero.Button 대역. 생성 인자를 기록하고 is_pressed 를 조작할 수 있다."""

    instances: list["FakeButton"] = []

    def __init__(self, pin, pull_up=None, bounce_time=None):
        self.pin = pin
        self.pull_up = pull_up
        self.bounce_time = bounce_time
        self.is_pressed = False
        self.closed = False
        FakeButton.instances.append(self)

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


def test_contact_closed_means_water_present(fake_gpiozero):
    sensor = FloatSwitchSensor(27)
    button = FakeButton.instances[0]

    button.is_pressed = True  # 접점 닫힘 (뜨개 올라감)
    reading = sensor.read()
    assert reading.raw_closed is True
    assert reading.water_present is True

    button.is_pressed = False  # 접점 열림 (뜨개 내려감)
    reading = sensor.read()
    assert reading.raw_closed is False
    assert reading.water_present is False


def test_invert_flips_interpretation_but_not_raw(fake_gpiozero):
    sensor = FloatSwitchSensor(27, invert=True)
    button = FakeButton.instances[0]

    button.is_pressed = True
    reading = sensor.read()
    assert reading.raw_closed is True  # 원시값은 그대로
    assert reading.water_present is False  # 해석만 반전

    button.is_pressed = False
    assert sensor.read().water_present is True


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


def test_cli_read_invert_flag_flips_interpretation(fake_gpiozero, capsys):
    from cli.water_level import main

    main(["read", "--invert"])
    payload = json.loads(capsys.readouterr().out)
    assert payload["raw_closed"] is False
    assert payload["water_present"] is True  # 열림 + invert = 물 있음
