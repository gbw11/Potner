package com.potner.light.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 어떤 구간의 조도 적분 결과다. 확정된 하루와 진행 중인 오늘 모두 같은 형태로 쓴다.
 */
public record DailyLightAccumulationSnapshot(
        BigDecimal accumulatedLuxHour,
        BigDecimal lightHours,
        long coveredSeconds,
        long sampleCount
) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 구간을 실제로 덮은 비율이다. 진행 중인 오늘은 경과 시간을 분모로 넘긴다.
     */
    public BigDecimal coveragePct(long windowSeconds) {
        if (windowSeconds <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(coveredSeconds)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(windowSeconds), 2, RoundingMode.HALF_UP)
                .min(HUNDRED.setScale(2, RoundingMode.HALF_UP));
    }
}
