package com.potner.light.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 진행 중인 오늘의 누적값이다.
 *
 * <p>하루가 끝나지 않았으므로 판정 결과를 담지 않는다. 앱은 {@code progressPct}로 진행률을 보여주고,
 * 판정은 다음 날 확정된 이력에서 확인한다.
 */
public record DailyLightTodayResponse(
        LocalDate lightDate,
        BigDecimal accumulatedLuxHour,
        BigDecimal targetLuxHour,
        BigDecimal progressPct,
        BigDecimal lightHours,
        BigDecimal targetPhotoperiodHours,
        BigDecimal coveragePct,
        long sampleCount
) {
}
