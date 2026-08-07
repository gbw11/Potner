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
    NAVIGATE("navigate", IotDeviceType.JETSON_ORIN),

    /**
     * 지도 제작(SLAM) 시작. 페이로드는 requestId 뿐이다.
     *
     * <p>로봇이 Nav2 를 내리고 slam_toolbox 를 띄운다. 둘 다 {@code map} 프레임을 발행하므로
     * 동시에 뜰 수 없다. OK 회신은 "SLAM 이 떴다"는 뜻이고, 그 뒤 사용자가 방향 버튼으로
     * 집을 돌며 지도를 그린다 — 주행은 기존 수동 주행 통로를 그대로 쓴다.
     */
    MAPPING_START("mapping-start", IotDeviceType.JETSON_ORIN),

    /**
     * 지도를 저장하고 제작을 끝낸다. 페이로드는 requestId 뿐이다.
     *
     * <p>로봇이 map_saver_cli 로 지도를 쓰고 slam_toolbox 를 내린 뒤 Nav2 를 그 지도로
     * 다시 띄운다. OK 회신은 저장과 Nav2 재기동까지 끝났다는 뜻이다 — 여기서 실패하면
     * 그린 지도를 잃으므로 결과를 반드시 확인해야 한다.
     */
    MAPPING_SAVE("mapping-save", IotDeviceType.JETSON_ORIN),

    /**
     * 저장하지 않고 제작을 끝낸다. 페이로드는 requestId 뿐이다.
     *
     * <p>지도가 겹쳐 그려졌을 때 버리고 다시 시작하는 통로다. 저장을 안 할 뿐 나머지는
     * {@link #MAPPING_SAVE} 와 같다 — slam_toolbox 를 내리고 이전 지도로 Nav2 를 되살린다.
     */
    MAPPING_CANCEL("mapping-cancel", IotDeviceType.JETSON_ORIN);

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
