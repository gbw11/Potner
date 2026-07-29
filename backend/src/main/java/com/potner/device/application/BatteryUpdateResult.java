package com.potner.device.application;

public enum BatteryUpdateResult {

    /** 잔량을 저장했다. */
    UPDATED,

    /** 등록되지 않은 장치가 보냈다. */
    DEVICE_NOT_FOUND,

    /** 장치는 있지만 로봇에 연결되지 않았다. 데이터가 깨진 경우다. */
    ROBOT_NOT_FOUND,

    /**
     * 배터리를 보고하도록 정해진 종류가 아닌 장치가 보냈다.
     *
     * <p>{@code robot.battery_percent} 가 컬럼 하나라 두 장치가 보내면 서로 덮어써 값이
     * 무의미해진다. 규격상 젯슨만 보내므로 나머지는 거부하고 로그로 드러낸다.
     */
    UNEXPECTED_DEVICE_TYPE
}
