package com.potner.device.application;

public enum RobotStateUpdateResult {

    /** 상태가 바뀌어 저장했다. */
    CHANGED,

    /** 같은 상태가 다시 왔다. 젯슨이 주기적으로 반복 발행하므로 이것이 대부분이다. */
    UNCHANGED,

    /** 등록되지 않은 장치가 보냈다. */
    DEVICE_NOT_FOUND,

    /** 장치는 있지만 로봇에 연결되지 않았다. 데이터가 깨진 경우다. */
    ROBOT_NOT_FOUND
}
