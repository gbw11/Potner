package com.potner.push.application;

import com.potner.alert.application.AlertOpenedEvent;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertPushMessageFactoryTest {

    private static final String ALERT_ID = "50000000-0000-0000-0000-0000000000ee";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";

    private final AlertPushMessageFactory messageFactory = new AlertPushMessageFactory();

    @ParameterizedTest
    @EnumSource(AlertMetricType.class)
    void everyMetricHasCopyForBothDirections(AlertMetricType metricType) {
        for (AlertDeviation deviation : supportedDeviations(metricType)) {
            PushMessage message = messageFactory.create(event(metricType, deviation, "로지"));

            assertThat(message.title()).isNotBlank();
            assertThat(message.body()).isNotBlank();
            // 서식 문자열이 그대로 나가면 사용자가 "%s의 ..." 를 보게 된다.
            assertThat(message.body()).doesNotContain("%s").contains("로지의");
        }
    }

    @Test
    void copyIsDistinctForEveryMetricAndDirection() {
        List<String> bodies = new ArrayList<>();
        for (AlertMetricType metricType : AlertMetricType.values()) {
            for (AlertDeviation deviation : supportedDeviations(metricType)) {
                bodies.add(messageFactory.create(event(metricType, deviation, "로지")).body());
            }
        }

        // 센서 지표 5종 × 방향 2종 + 설비 알림 2종(각 한 방향). 같은 문구가 겹치면 사용자가
        // 무엇이 문제인지 구분할 수 없다.
        assertThat(bodies).hasSize(12).doesNotHaveDuplicates();
    }

    @Test
    void drainageTrayAsksForEmptying() {
        PushMessage message = messageFactory.create(
                event(AlertMetricType.DRAINAGE_TRAY, AlertDeviation.HIGH, "로지")
        );

        assertThat(message.title()).isEqualTo("배수트레이를 비워주세요");
        assertThat(message.body()).isEqualTo("로지의 배수트레이에 물이 찼어요. 넘치지 않도록 비워 주세요.");
    }

    @Test
    void stationWaterLowAsksForARefill() {
        PushMessage message = messageFactory.create(
                event(AlertMetricType.STATION_WATER_LOW, AlertDeviation.LOW, "로지")
        );

        assertThat(message.title()).isEqualTo("스테이션 물이 부족해요");
        assertThat(message.body()).isEqualTo("로지의 원활한 급수를 위해 스테이션에 물을 보충해 주세요.");
    }

    /**
     * 설비 알림은 한 방향으로만 열린다 — 물 부족은 불리언이라 LOW, 트레이는 넘칠 때만
     * 문제이므로 HIGH 다. 반대 방향은 여는 쪽의 버그다.
     */
    private static List<AlertDeviation> supportedDeviations(AlertMetricType metricType) {
        return switch (metricType) {
            case STATION_WATER_LOW -> List.of(AlertDeviation.LOW);
            case DRAINAGE_TRAY -> List.of(AlertDeviation.HIGH);
            default -> List.of(AlertDeviation.values());
        };
    }

    @Test
    void lowSoilMoistureAsksForWater() {
        PushMessage message = messageFactory.create(
                event(AlertMetricType.SOIL_MOISTURE, AlertDeviation.LOW, "로지")
        );

        assertThat(message.title()).isEqualTo("흙이 말랐어요");
        assertThat(message.body()).isEqualTo("로지의 토양 수분이 기준보다 낮아요. 물을 주세요.");
    }

    @Test
    void dailyMetricCopyRefersToYesterday() {
        // 광량은 전일분을 새벽에 판정한다. 오늘 일처럼 읽히면 사용자가 지금 조치하려 한다.
        assertThat(messageFactory.create(
                event(AlertMetricType.DAILY_LIGHT, AlertDeviation.LOW, "로지")).body())
                .startsWith("어제 ");
        assertThat(messageFactory.create(
                event(AlertMetricType.PHOTOPERIOD, AlertDeviation.HIGH, "로지")).body())
                .startsWith("어제 ");
    }

    @Test
    void dataCarriesTheIdentifiersTheAppNeeds() {
        PushMessage message = messageFactory.create(
                event(AlertMetricType.TEMPERATURE, AlertDeviation.HIGH, "로지")
        );

        assertThat(message.data()).containsOnly(
                java.util.Map.entry("type", "SENSOR_ALERT"),
                java.util.Map.entry("alertId", ALERT_ID),
                java.util.Map.entry("plantId", PLANT_ID),
                java.util.Map.entry("route", "/alerts")
        );
    }

    @Test
    void nicknameWithFormatSpecifierIsNotInterpreted() {
        // 별명은 사용자 입력이다. 서식 문자열의 인자로만 쓰여야 한다.
        PushMessage message = messageFactory.create(
                event(AlertMetricType.HUMIDITY, AlertDeviation.LOW, "100%s%d")
        );

        assertThat(message.body()).startsWith("100%s%d의 ");
    }

    private AlertOpenedEvent event(
            AlertMetricType metricType,
            AlertDeviation deviation,
            String nickname
    ) {
        return new AlertOpenedEvent(
                ALERT_ID,
                "10000000-0000-0000-0000-0000000000aa",
                PLANT_ID,
                nickname,
                metricType,
                deviation
        );
    }
}
