package com.potner.command.domain;

/**
 * 서버가 장치에 보내는 명령의 종류다.
 *
 * <p>{@code commandName} 은 MQTT 토픽의 마지막 조각이 된다
 * ({@code potner/device/{uid}/command/{name}}, 회신은 {@code .../result/{name}}).
 * 라즈베리 수신기가 구독하는 이름과 같아야 하므로 enum 이름을 소문자로 바꿔 쓰지 않고
 * 명시적으로 둔다.
 *
 * <p>지금은 둘 다 라즈베리가 받는다. 펌프와 카메라가 라즈베리에 달려 있기 때문이고,
 * 젯슨으로 가는 이동(navigate)은 두뇌 중재가 정해진 뒤에 붙는다.
 */
public enum DeviceCommandType {

    /** 급수. 페이로드에 급수량(ml)이 실린다. */
    WATER("water"),

    /** 촬영. 페이로드는 requestId 뿐이다. */
    CAPTURE("capture");

    private final String commandName;

    DeviceCommandType(String commandName) {
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }
}
