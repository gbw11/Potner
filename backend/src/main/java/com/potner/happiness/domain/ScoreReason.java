package com.potner.happiness.domain;

import com.potner.alert.domain.AlertMetricType;

import java.util.Optional;

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
     *
     * <p>물 부족과 배수트레이는 감점하지 않는다. 둘 다 사람이 손볼 설비 상태이지 식물의 상태가
     * 아니다. 저수조가 비어 흙이 실제로 마르면 토양수분 알림이 따로 감점하므로, 여기서도 깎으면
     * 같은 사실로 두 번 깎는다. 트레이가 찬 것은 오히려 물을 잘 준 결과다.
     */
    public static Optional<ScoreReason> ofAlert(AlertMetricType metricType) {
        return switch (metricType) {
            case TEMPERATURE -> Optional.of(TEMPERATURE_ALERT);
            case HUMIDITY -> Optional.of(HUMIDITY_ALERT);
            case SOIL_MOISTURE -> Optional.of(SOIL_MOISTURE_ALERT);
            case DAILY_LIGHT -> Optional.of(DAILY_LIGHT_ALERT);
            case PHOTOPERIOD -> Optional.of(PHOTOPERIOD_ALERT);
            case STATION_WATER_LOW, DRAINAGE_TRAY -> Optional.empty();
        };
    }
}
