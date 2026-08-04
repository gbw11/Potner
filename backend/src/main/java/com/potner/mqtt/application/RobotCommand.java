package com.potner.mqtt.application;

import java.util.Objects;

/**
 * 서버가 로봇에 보내는 명령 한 건이다.
 *
 * <p>{@code name} 은 토픽의 마지막 조각이 된다. {@code potner/device/{uid}/command/{name}} 이므로
 * "expression" 은 표정 갱신, 나중에 붙을 "navigate" 는 이동, "watering" 은 급수가 된다.
 *
 * <p>{@code payload} 는 Jackson 이 직렬화할 수 있는 값이어야 한다. 레코드를 넘기면 필드 이름이
 * 그대로 JSON 키가 된다.
 */
public record RobotCommand(String name, Object payload) {

    public RobotCommand {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    /** 로봇 디스플레이의 표정을 갱신한다. 디스플레이는 젯슨에 달려 있다. */
    public static RobotCommand expression(Object payload) {
        return new RobotCommand("expression", payload);
    }

    /**
     * 방향 버튼으로 로봇을 직접 민다. 바퀴는 젯슨에 달려 있다.
     *
     * <p>목적지 이동("navigate")과 토픽이 다르다. 이쪽은 지도 좌표 없이 속도만 받아 정해진
     * 시간만큼 움직이고 스스로 멈추므로, 로봇의 처리도 자율 주행과 섞이지 않아야 한다.
     */
    public static RobotCommand drive(Object payload) {
        return new RobotCommand("drive", payload);
    }
}
