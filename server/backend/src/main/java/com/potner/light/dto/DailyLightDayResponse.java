package com.potner.light.dto;

import com.potner.light.domain.DailyLightStatus;
import com.potner.light.domain.PlantDailyLight;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 확정된 하루의 광량 판정 결과다. */
public record DailyLightDayResponse(
        LocalDate lightDate,
        BigDecimal accumulatedLuxHour,
        BigDecimal targetLuxHour,
        BigDecimal thresholdMinLuxHour,
        BigDecimal thresholdMaxLuxHour,
        BigDecimal lightHours,
        BigDecimal targetPhotoperiodHours,
        BigDecimal coveragePct,
        long sampleCount,
        DailyLightStatus lightStatus,
        DailyLightStatus photoperiodStatus
) {
    public static DailyLightDayResponse from(PlantDailyLight daily) {
        return new DailyLightDayResponse(
                daily.getLightDate(),
                daily.getAccumulatedLuxHour(),
                daily.getTargetLuxHour(),
                daily.getThresholdMinLuxHour(),
                daily.getThresholdMaxLuxHour(),
                daily.getLightHours(),
                daily.getTargetPhotoperiodHours(),
                daily.getCoveragePct(),
                daily.getSampleCount(),
                daily.getLightStatus(),
                daily.getPhotoperiodStatus()
        );
    }
}
