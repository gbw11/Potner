package com.potner.location.application;

/**
 * 물 부족 보고 한 건을 반영한 결과다.
 *
 * <p>{@code BatteryUpdateResult} 와 같은 방식이다. MQTT 는 응답이 없는 경로라 어느 단계에서
 * 멈췄는지가 로그로 남아야 원인을 찾을 수 있다.
 */
public enum WaterLowUpdateResult {

    /** 부족 상태로 처음 바뀌었다. 알림이 나간다. */
    BECAME_LOW,

    /** 이미 부족 상태에서 온 반복 보고다. 보고 시각만 갱신하고 알림은 내지 않는다. */
    STILL_LOW,

    /** 보충되어 부족이 해제됐다. 다음 부족 때 알림이 다시 나갈 수 있게 된다. */
    CLEARED,

    /** 부족이 아닌 상태에서 온 정상 보고다. 아무것도 바꾸지 않는다. */
    ALREADY_CLEAR,

    /** 등록되지 않은 장치가 보냈다. */
    DEVICE_NOT_FOUND,

    /** 장치는 있지만 로봇에 급수 스테이션이 등록되어 있지 않다. */
    STATION_NOT_FOUND
}
