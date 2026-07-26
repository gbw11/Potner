package com.potner.alert.application;

import com.potner.alert.domain.AlertDeviation;
import com.potner.sensor.application.SensorThresholds;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 이상 상태 진입과 복귀를 판정한다.
 *
 * <p>두 가지 잡음을 서로 다른 방법으로 걸러낸다.
 * 순간 스파이크는 최근 측정값의 중앙값을 쓰는 것으로 지우고,
 * 기준선 근처에서 값이 왕복하며 알림이 반복되는 것은 진입과 복귀 기준을 다르게 두어 막는다.
 * 중앙값을 쓰기 때문에 표본 하나가 튀어도 판정이 밀리지 않고, 반대로 표본 하나가 정상이라는 이유로
 * 실제 이상을 놓치는 일도 없다.
 */
public final class DeviationEvaluator {

    private DeviationEvaluator() {
    }

    /** 표본 수가 홀수라는 전제에서 중앙값을 돌려준다. */
    public static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Median requires at least one value");
        }
        List<BigDecimal> sorted = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        return sorted.get(sorted.size() / 2);
    }

    /** 기준을 벗어난 방향을 돌려준다. 범위 안이면 빈 값이다. */
    public static Optional<AlertDeviation> detect(BigDecimal value, SensorThresholds thresholds) {
        if (value.compareTo(thresholds.min()) < 0) {
            return Optional.of(AlertDeviation.LOW);
        }
        if (value.compareTo(thresholds.max()) > 0) {
            return Optional.of(AlertDeviation.HIGH);
        }
        return Optional.empty();
    }

    /**
     * 활성 이상 상태를 해제할 만큼 회복했는지 판정한다.
     *
     * <p>진입 기준을 그대로 쓰면 기준선을 조금 넘었다 되돌아오는 것만으로 해제와 재생성이 반복된다.
     * 그래서 복귀에는 범위 폭에 비례한 여유값을 요구한다.
     */
    public static boolean recovered(
            BigDecimal value,
            SensorThresholds thresholds,
            AlertDeviation activeDeviation,
            BigDecimal hysteresisRatio
    ) {
        BigDecimal band = thresholds.max()
                .subtract(thresholds.min())
                .multiply(hysteresisRatio);
        return switch (activeDeviation) {
            case LOW -> value.compareTo(thresholds.min().add(band)) >= 0;
            case HIGH -> value.compareTo(thresholds.max().subtract(band)) <= 0;
        };
    }
}
