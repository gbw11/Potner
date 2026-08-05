from __future__ import annotations

from src.actuators import MockFan
from src.mqtt.fan_command import FanCommandListener, build_fan_listener


def _listener(fan: MockFan | None = None, **kwargs) -> FanCommandListener:
    kwargs.setdefault("blow_run_sec", 0.05)
    return FanCommandListener(
        fan=fan or MockFan(),
        host="localhost",
        device_id="pi-test",
        username="u",
        password="p",
        command_topic="t/cmd",
        result_topic="t/result",
        **kwargs,
    )


def test_server_payload_values_are_ignored():
    """앱에 송풍 버튼만 있으므로 서버가 뭘 보내든 기기 고정값으로 가동한다."""
    fan = MockFan()
    listener = _listener(fan, blow_speed_pct=70.0, blow_run_sec=0.05)
    # 서버 실규약 — seconds 30 을 보내도 기기 시간(0.05s)만 돈다
    result = listener.handle_command(
        {"seconds": 30, "requestId": "e5231042-d4dd-442f-87e7-b9275a82d5b9"}
    )
    assert result["status"] == "OK"
    assert result["appliedSpeedPct"] == 70.0
    assert result["durationSec"] == 0.05
    # requestId 는 서버측 매칭용이라 그대로 되돌려준다
    assert result["requestId"] == "e5231042-d4dd-442f-87e7-b9275a82d5b9"
    # run_for 는 가동 후 정지까지 보장한다
    assert fan.speed_history == [70.0, 0.0]


def test_speed_and_duration_in_payload_do_not_override():
    fan = MockFan()
    result = _listener(fan, blow_speed_pct=100.0).handle_command(
        {"speed": 20, "durationSec": 600, "on": False}
    )
    assert result["appliedSpeedPct"] == 100.0
    assert result["durationSec"] == 0.05
    assert fan.speed_history == [100.0, 0.0]


def test_empty_payload_is_a_button_press():
    fan = MockFan()
    result = _listener(fan).handle_command({})
    assert result["status"] == "OK"
    assert "requestId" not in result
    assert fan.speed_history == [100.0, 0.0]


def test_result_carries_device_envelope():
    result = _listener().handle_command({"requestId": "r1"})
    assert result["deviceId"] == "pi-test"
    assert result["messageId"]
    assert result["measuredAt"].endswith("Z")


def test_busy_while_running():
    """가동 중 새 명령은 BUSY — 앱에서 버튼을 연타해도 중복 가동하지 않는다."""
    fan = MockFan()
    listener = _listener(fan)
    listener._run_lock.acquire()  # 가동 중인 상태를 그대로 재현
    try:
        result = listener.handle_command({"requestId": "r2"})
    finally:
        listener._run_lock.release()
    assert result["status"] == "BUSY"
    assert result["requestId"] == "r2"
    assert fan.speed_history == []  # 팬은 건드리지 않는다


def test_fan_error_is_reported_not_raised():
    class BrokenFan(MockFan):
        def run_for(self, seconds, speed_pct=100.0, **kwargs):
            raise RuntimeError("gpio busy")

    result = _listener(BrokenFan()).handle_command({"requestId": "r3"})
    assert result["status"] == "ERROR"
    assert "gpio busy" in result["error"]
    assert result["requestId"] == "r3"


def test_invalid_json_still_blows():
    """페이로드 값을 쓰지 않으므로 깨진 JSON 도 거부 사유가 아니다 (requestId 만 손실)."""
    fan = MockFan()
    listener = _listener(fan)
    listener._handle_raw("not json at all")
    assert fan.speed_history == [100.0, 0.0]


def test_build_fan_listener_disabled_without_mqtt_or_fan():
    assert build_fan_listener({"mqtt": {"enabled": False}, "fan": {"enabled": True}}) is None
    assert (
        build_fan_listener(
            {"platform": "mock", "mqtt": {"enabled": True}, "fan": {"enabled": False}}
        )
        is None
    )


def test_build_fan_listener_reads_config(monkeypatch):
    monkeypatch.setenv("MQTT_PASSWORD", "secret")
    listener = build_fan_listener(
        {
            "platform": "mock",
            "mqtt": {
                "enabled": True,
                "host": "broker",
                "device_id": "raspberry-01",
                "username": "raspberry-01",
                "fan_command_topic": "c/fan",
                "fan_result_topic": "r/fan",
            },
            "fan": {"enabled": True, "blow_speed_pct": 60, "blow_run_sec": 4},
        }
    )
    assert listener is not None
    assert listener.command_topic == "c/fan"
    assert listener.result_topic == "r/fan"
    assert listener.blow_speed_pct == 60.0
    assert listener.blow_run_sec == 4.0


def test_build_fan_listener_falls_back_on_bad_config(monkeypatch):
    """못 쓸 값이면 기본값(100% / 10초)으로 — 팬이 안 도는 것보다 낫다."""
    monkeypatch.setenv("MQTT_PASSWORD", "secret")
    listener = build_fan_listener(
        {
            "platform": "mock",
            "mqtt": {"enabled": True, "username": "raspberry-01"},
            "fan": {"enabled": True, "blow_speed_pct": "abc", "blow_run_sec": 0},
        }
    )
    assert listener is not None
    assert listener.blow_speed_pct == 100.0
    assert listener.blow_run_sec == 10.0
    # 토픽 폴백은 ACL 계정(username) 기준
    assert listener.command_topic == "potner/device/raspberry-01/command/fan"
    assert listener.result_topic == "potner/device/raspberry-01/result/fan"


def test_raspberry_yaml_is_single_source_of_blow_settings():
    """실기 config 가 풍량·시간·토픽의 단일 출처인지 가드."""
    from src.config import load_config

    config = load_config("config/raspberry_pi.yaml")
    fan_cfg = config["fan"]
    # 낮은 duty 는 기동 토크가 부족해 진동만 한다(2026-08-05 실측) → 100% 고정
    assert fan_cfg["blow_speed_pct"] == 100
    assert fan_cfg["blow_run_sec"] == 10
    mqtt_cfg = config["mqtt"]
    assert mqtt_cfg["fan_command_topic"] == "potner/device/raspberry-01/command/fan"
    assert mqtt_cfg["fan_result_topic"] == "potner/device/raspberry-01/result/fan"
