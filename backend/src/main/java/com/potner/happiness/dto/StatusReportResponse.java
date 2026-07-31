package com.potner.happiness.dto;

import com.potner.happiness.domain.ScoreReason;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 하루를 돌아보는 상태 리포트다. 일기 상세 화면이 쓴다.
 *
 * <p>홈 화면의 등급({@code /happiness})과 다르다. 등급은 지금을 말하고 이 점수는 그날 하루를
 * 말한다. 일기가 없는 날에도 리포트는 있어야 하므로 일기 응답에 싣지 않고 따로 나간다.
 *
 * @param happinessScore 0~100. <strong>그날 측정값이 아예 없으면 {@code null}</strong> 이다.
 *                       0점으로 주면 "최악의 하루" 와 "기록이 없는 하루" 를 구별할 수 없다.
 * @param adjustments    100 에서 움직인 이유. 점수가 100 이면 빈 목록이다.
 * @param lightHours     임계 조도 이상을 받은 실측 시간. 광량 집계가 아직 없으면 {@code null}.
 *                       광량은 하루가 끝난 뒤 새벽에 확정되므로 오늘 것은 비어 있다.
 * @param wateredMl      그날 실제로 나간 급수량의 합({@code device_command.dispensed_ml})이다.
 *                       급수 기록이 없으면 {@code null} 이며 0 과 구분된다 — 0 은 급수를
 *                       시도했으나 한 방울도 안 나간 경우다.
 */
public record StatusReportResponse(
        String plantId,
        LocalDate date,
        Integer happinessScore,
        List<ScoreAdjustment> adjustments,
        BigDecimal lightHours,
        BigDecimal wateredMl
) {

    public StatusReportResponse {
        adjustments = List.copyOf(adjustments);
    }

    public record ScoreAdjustment(ScoreReason reason, int points) {
    }
}
