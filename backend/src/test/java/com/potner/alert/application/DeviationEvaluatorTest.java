package com.potner.alert.application;

import com.potner.alert.domain.AlertDeviation;
import com.potner.sensor.application.SensorThresholds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviationEvaluatorTest {

    /** 바질 발아기 토양수분 기준. 여유값은 (55 - 40) * 0.1 = 1.5 다. */
    private static final SensorThresholds SOIL = new SensorThresholds(
            new BigDecimal("40.00"),
            new BigDecimal("55.00")
    );
    private static final BigDecimal RATIO = new BigDecimal("0.1");

    @Test
    void medianOfOddSamplesIsMiddleValue() {
        assertThat(DeviationEvaluator.median(values("35", "41", "34"))).isEqualByComparingTo("35");
        assertThat(DeviationEvaluator.median(values("7"))).isEqualByComparingTo("7");
    }

    @Test
    void medianIgnoresSingleSpike() {
        // 평균은 36.3 으로 끌려가지만 중앙값은 스파이크의 영향을 받지 않는다.
        assertThat(DeviationEvaluator.median(values("45", "44", "20"))).isEqualByComparingTo("44");
        assertThat(DeviationEvaluator.median(values("45", "44", "9999"))).isEqualByComparingTo("45");
    }

    @Test
    void medianRejectsEmptySamples() {
        assertThatThrownBy(() -> DeviationEvaluator.median(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noisySamplesStillDetectDeviationBecauseMedianIsUsed() {
        // 표본 하나가 정상이라는 이유로 실제 이상을 놓치지 않는다.
        assertThat(DeviationEvaluator.detect(
                DeviationEvaluator.median(values("35", "41", "34")), SOIL))
                .contains(AlertDeviation.LOW);
    }

    @Test
    void detectUsesEntryThresholdsInclusively() {
        assertThat(DeviationEvaluator.detect(new BigDecimal("39.99"), SOIL))
                .contains(AlertDeviation.LOW);
        assertThat(DeviationEvaluator.detect(new BigDecimal("40.00"), SOIL)).isEmpty();
        assertThat(DeviationEvaluator.detect(new BigDecimal("55.00"), SOIL)).isEmpty();
        assertThat(DeviationEvaluator.detect(new BigDecimal("55.01"), SOIL))
                .contains(AlertDeviation.HIGH);
    }

    @Test
    void lowAlertRequiresRecoveryAboveHysteresisBand() {
        assertThat(recovered("40.00", AlertDeviation.LOW)).isFalse();
        assertThat(recovered("41.49", AlertDeviation.LOW)).isFalse();
        assertThat(recovered("41.50", AlertDeviation.LOW)).isTrue();
        assertThat(recovered("50.00", AlertDeviation.LOW)).isTrue();
    }

    @Test
    void highAlertRequiresRecoveryBelowHysteresisBand() {
        assertThat(recovered("55.00", AlertDeviation.HIGH)).isFalse();
        assertThat(recovered("53.51", AlertDeviation.HIGH)).isFalse();
        assertThat(recovered("53.50", AlertDeviation.HIGH)).isTrue();
        assertThat(recovered("45.00", AlertDeviation.HIGH)).isTrue();
    }

    @Test
    void oscillationAroundThresholdNeverRecovers() {
        // 기준선 40 근처를 39.6 ~ 41.2 로 왕복해도 복귀 기준 41.5 를 넘지 못하므로
        // 활성 Alert 가 해제되지 않고, 따라서 재생성도 일어나지 않는다.
        for (String value : List.of("39.60", "40.30", "39.80", "41.20", "40.10")) {
            assertThat(recovered(value, AlertDeviation.LOW))
                    .as("value=%s", value)
                    .isFalse();
        }
    }

    private boolean recovered(String value, AlertDeviation activeDeviation) {
        return DeviationEvaluator.recovered(new BigDecimal(value), SOIL, activeDeviation, RATIO);
    }

    private List<BigDecimal> values(String... raw) {
        return List.of(raw).stream().map(BigDecimal::new).toList();
    }
}
