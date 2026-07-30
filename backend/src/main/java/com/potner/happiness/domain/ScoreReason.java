package com.potner.happiness.domain;

import com.potner.alert.domain.AlertMetricType;

/**
 * 점수가 100에서 움직인 이유다.
 *
 * <p>사용자가 "왜 95점인지" 를 물을 때 앱이 답할 수 있어야 하고, 시연 중에 점수가 이상하면
 * 원인을 바로 봐야 한다. 최종 점수만 주면 둘 다 못 한다.
 */
public enum ScoreReason {

    TEMPERATURE_ALERT,
    HUMIDITY_ALERT,
    SOIL_MOISTURE_ALERT,
    DAILY_LIGHT_ALERT,
    PHOTOPERIOD_ALERT,

    /** 그날 꽃이 폈다. 유일한 가점이다. */
    BLOOMED;

    /**
     * 이상 알림 지표를 감점 이유로 옮긴다.
     *
     * <p>{@code default} 를 두지 않는다. 지표를 추가하면 컴파일 단계에서 이 자리가 걸린다.
     */
    public static ScoreReason ofAlert(AlertMetricType metricType) {
        return switch (metricType) {
            case TEMPERATURE -> TEMPERATURE_ALERT;
            case HUMIDITY -> HUMIDITY_ALERT;
            case SOIL_MOISTURE -> SOIL_MOISTURE_ALERT;
            case DAILY_LIGHT -> DAILY_LIGHT_ALERT;
            case PHOTOPERIOD -> PHOTOPERIOD_ALERT;
        };
    }
}
