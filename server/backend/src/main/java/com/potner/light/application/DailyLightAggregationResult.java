package com.potner.light.application;

import com.potner.alert.application.AlertEvaluationResult;
import com.potner.light.domain.DailyLightStatus;

import java.time.LocalDate;

public record DailyLightAggregationResult(
        LocalDate lightDate,
        DailyLightStatus lightStatus,
        DailyLightStatus photoperiodStatus,
        AlertEvaluationResult lightAlert,
        AlertEvaluationResult photoperiodAlert
) {

    public static DailyLightAggregationResult skipped(LocalDate lightDate, DailyLightStatus reason) {
        return new DailyLightAggregationResult(
                lightDate,
                reason,
                reason,
                AlertEvaluationResult.UNCHANGED,
                AlertEvaluationResult.UNCHANGED
        );
    }
}
