package com.potner.sensor.domain;

public enum SensorStatus {

    /** 측정값이 적용 생육 기준의 하한보다 낮다. */
    LOW,

    /** 측정값이 적용 생육 기준 범위 안에 있다. */
    NORMAL,

    /** 측정값이 적용 생육 기준의 상한보다 높다. */
    HIGH,

    /**
     * 순간값으로 판정하지 않는 센서다. 조도가 여기에 해당한다.
     * 밤에는 0 lux가 정상이므로 생육 기준은 일일 누적 광량으로만 표현된다.
     */
    NOT_APPLICABLE,

    /** 측정값이 아직 수집되지 않았다. */
    NO_DATA,

    /** 최신 측정값이 허용 신선도를 넘겨 오래되었다. 장치가 조용한 상태다. */
    STALE
}
