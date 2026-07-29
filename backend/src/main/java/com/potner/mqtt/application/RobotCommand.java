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
}
