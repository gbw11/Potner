package com.potner.command.domain;

import com.potner.device.domain.IotDeviceType;

/**
 * 서버가 장치에 보내는 명령의 종류다.
 *
 * <p>{@code commandName} 은 MQTT 토픽의 마지막 조각이 된다
 * ({@code potner/device/{uid}/command/{name}}, 회신은 {@code .../result/{name}}).
 * 장치 수신기가 구독하는 이름과 같아야 하므로 enum 이름을 소문자로 바꿔 쓰지 않고
 * 명시적으로 둔다.
 *
 * <p>{@code targetDeviceType} 이 명령을 받을 장치를 정한다. 펌프·팬·카메라는 스테이션의
 * 라즈베리에, 바퀴는 젯슨에 붙어 있다. 발행 서비스는 로봇의 장치 중 이 종류를 찾아 보낸다.
 */
public enum DeviceCommandType {

    /** 급수. 페이로드에 급수량(ml)이 실린다. */
    WATER("water", IotDeviceType.RASPBERRY_PI),

    /** 촬영. 페이로드는 requestId 뿐이다. */
    CAPTURE("capture", IotDeviceType.RASPBERRY_PI),

    /** 송풍. 토양 수분 과다를 말리는 데 쓴다. 페이로드에 가동 시간(초)이 실린다. */
    FAN("fan", IotDeviceType.RASPBERRY_PI),

    /**
     * 이동. 페이로드에 목적지 이름과 지도 좌표가 실린다.
     *
     * <p>좌표를 함께 보내는 이유는 로봇이 서버의 위치 저장소를 모르기 때문이다. 이름만 보내면
     * 로봇 파라미터의 좌표를 쓰게 되는데, 그러면 좌표의 출처가 둘이 되어 어긋난다.
     */
    NAVIGATE("navigate", IotDeviceType.JETSON_ORIN);

    private final String commandName;
    private final IotDeviceType targetDeviceType;

    DeviceCommandType(String commandName, IotDeviceType targetDeviceType) {
        this.commandName = commandName;
        this.targetDeviceType = targetDeviceType;
    }

    public String commandName() {
        return commandName;
    }

    public IotDeviceType targetDeviceType() {
        return targetDeviceType;
    }
}
