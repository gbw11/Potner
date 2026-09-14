package com.potner.light.domain;

import java.math.BigDecimal;

/**
 * 하루치 조도 표본을 시간 적분한 결과다.
 *
 * <p>{@code coveredSeconds}는 실제로 적분에 쓰인 구간의 합이다. 장치가 조용했던 시간은 여기에
 * 포함되지 않으므로 하루(86400초) 대비 비율로 데이터 충분성을 판단할 수 있다.
 */
public interface DailyLightAccumulation {

    BigDecimal getAccumulatedLuxHour();

    BigDecimal getLightHours();

    Long getCoveredSeconds();

    Long getSampleCount();
}
