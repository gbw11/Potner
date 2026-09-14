package com.potner.light.domain;

public enum DailyLightStatus {

    /** 하루 누적값이 허용 범위의 하한보다 낮다. */
    LOW,

    /** 하루 누적값이 허용 범위 안에 있다. */
    NORMAL,

    /** 하루 누적값이 허용 범위의 상한보다 높다. */
    HIGH,

    /** 장치가 오래 조용해 하루를 대표할 만큼 표본이 모이지 않았다. */
    INSUFFICIENT_DATA,

    /** 적용 생육 기준에 허용 범위가 없어 판정할 수 없다. */
    NOT_APPLICABLE;

    public boolean isDeviation() {
        return this == LOW || this == HIGH;
    }
}
