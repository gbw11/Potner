"""ESP32 펌웨어 상수가 파이썬 쪽과 어긋나지 않는지 확인합니다.

같은 물리량이 두 언어에 따로 적혀 있습니다.

    src/potner_firmware/src/main.cpp        펌웨어가 PID를 돌릴 때 쓰는 값
    src/potner_base/potner_base/kinematics.py  젯슨이 오도메트리를 만들 때 쓰는 값

어긋나도 아무 에러가 없습니다. 대신 **"명령한 속도"와 "실제 속도"와 "지도상
이동거리"가 셋 다 달라집니다.** 어느 쪽이 틀렸는지 알 수 없어서 PID 게인을
붙들고 헤매게 됩니다.

실제로 바퀴 지름을 75mm 로 확정한 뒤 yaml 과 파이썬만 고치고 펌웨어는
0.065 로 남아 있었습니다. 그대로였다면 펌웨어가 속도를 15% 낮게 계산해서
명령보다 빠르게 돌았을 것입니다. CI 는 C++ 을 빌드하지 않으므로 소스를
읽어서 대조합니다.
"""

import re
from pathlib import Path

import pytest

from potner_base.kinematics import DriveConfig

REPO = Path(__file__).resolve().parent.parent
FIRMWARE = REPO / "src" / "potner_firmware" / "src" / "main.cpp"
PLATFORMIO = REPO / "src" / "potner_firmware" / "platformio.ini"


def firmware_constants():
    """main.cpp 의 constexpr 숫자 상수를 뽑아냅니다."""
    source = FIRMWARE.read_text(encoding="utf-8")
    pattern = r"constexpr\s+(?:float|int|uint32_t)\s+(\w+)\s*=\s*([-\d.]+)f?\s*;"
    return {name: float(value) for name, value in re.findall(pattern, source)}


CONSTANTS = firmware_constants()


def test_상수를_읽어냈다():
    """정규식이 형식 변경을 못 따라가면 나머지 테스트가 조용히 통과합니다."""
    assert "WHEEL_DIAMETER_M" in CONSTANTS
    assert "COUNTS_PER_REV" in CONSTANTS
    assert "MAX_WHEEL_MPS" in CONSTANTS


def test_바퀴_지름이_파이썬과_일치한다():
    assert CONSTANTS["WHEEL_DIAMETER_M"] == pytest.approx(
        DriveConfig().wheel_diameter
    ), "펌웨어를 다시 올려야 합니다 — 젯슨만 고치면 속도가 어긋납니다"


def test_엔코더_분해능이_파이썬과_일치한다():
    assert CONSTANTS["COUNTS_PER_REV"] == pytest.approx(DriveConfig().counts_per_rev)


def test_최대_바퀴속도가_파이썬과_일치한다():
    """젯슨이 이미 제한해서 보내지만 펌웨어도 한 번 더 걷어냅니다.

    펌웨어 쪽이 더 크면 젯슨이 잘못된 값을 보냈을 때 그대로 나갑니다.
    """
    assert CONSTANTS["MAX_WHEEL_MPS"] == pytest.approx(DriveConfig().max_wheel_speed)


def test_PWM_주파수가_가청대역_밖이다():
    """PCA9685 대신 ESP32 를 쓴 이유입니다. 20kHz 아래로 내리면 모터가 웁니다."""
    assert CONSTANTS["PWM_FREQ_HZ"] >= 18000


def test_개루프_듀티_상한이_있다():
    """브링업용 D 명령은 PID 를 거치지 않습니다. 상한이 없으면 배선을
    확인하려고 보낸 값이 그대로 전속이 됩니다."""
    assert 0.0 < CONSTANTS["MAX_OPEN_DUTY"] <= 0.5


def test_엔코더는_입력전용_핀을_쓴다():
    """GPIO 34~39 는 입력 전용이라 실수로 출력으로 쓸 수 없습니다.

    ESP32-S3 로 보드를 바꾸면 이 핀들이 없어서 여기서 걸립니다.
    """
    for name in ("PIN_L_ENC_A", "PIN_L_ENC_B", "PIN_R_ENC_A", "PIN_R_ENC_B"):
        assert 34 <= CONSTANTS[name] <= 39, f"{name} 이 입력 전용 핀이 아닙니다"


def test_스트래핑_핀을_쓰지_않는다():
    """GPIO 0·2·12·15 는 부팅 모드를 정합니다. 여기에 모터 드라이버를
    물리면 보드가 부팅하지 못하거나 업로드가 안 됩니다."""
    strapping = {0.0, 2.0, 12.0, 15.0}
    pins = {
        name: value for name, value in CONSTANTS.items() if name.startswith("PIN_")
    }

    conflicts = [name for name, value in pins.items() if value in strapping]
    assert not conflicts, f"스트래핑 핀을 쓰고 있습니다: {conflicts}"


def test_보드가_S3_가_아니다():
    """S3 는 GPIO 34~39 가 없어서 이 펌웨어의 엔코더 핀이 성립하지 않습니다."""
    config = PLATFORMIO.read_text(encoding="utf-8")
    board = re.search(r"^board\s*=\s*(\S+)", config, re.M)

    assert board, "platformio.ini 에서 board 를 찾지 못했습니다"
    assert "s3" not in board.group(1).lower()
    assert "s2" not in board.group(1).lower()
    assert "c3" not in board.group(1).lower()
