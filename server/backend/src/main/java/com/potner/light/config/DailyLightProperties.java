package com.potner.light.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 일일 광량 집계 및 판정 정책이다.
 *
 * <p>조도는 순간값이 아니라 하루 누적으로 판정한다. 밤에는 0 lux가 정상이고 생육 기준도
 * 일일 누적 광량과 일조 시간으로 표현되어 있기 때문이다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.daily-light")
public record DailyLightProperties(

        /**
         * 이 값 이상을 "빛을 받는 중"으로 본다. 일조 시간 실측에 쓰인다.
         * 단일식물은 실내 조명만으로도 개화가 방해되므로 농학적으로는 더 낮은 값이 맞지만,
         * 저가 조도 센서의 노이즈를 고려해 여유를 둔 값에서 출발한다.
         */
        @NotNull @PositiveOrZero BigDecimal lightOnThresholdLux,

        /**
         * 표본 사이 간격을 이 값으로 제한한다. 장치가 오래 조용했을 때 마지막 측정값이
         * 그 시간 내내 유지된 것으로 계산되는 것을 막는다.
         *
         * <p>장치의 발행 주기보다 넉넉히 커야 한다. 주기보다 작으면 매 구간이 잘려 누적
         * 광량이 계통적으로 과소 계산된다. 반대로 지나치게 크면 장치가 죽어 있는 시간이
         * 마지막 값으로 채워져 과대 계산된다 — 둘 다 조용히 틀린 값을 만든다.
         */
        @Positive long maxGapSeconds,

        /** 집계 구간이 하루를 이 비율만큼 덮지 못하면 판정하지 않는다. */
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal minCoveragePct,

        /** 목표 일조 시간 대비 허용 비율. photoperiod_hours에는 별도 허용 범위 컬럼이 없다. */
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal photoperiodToleranceRatio,

        /** 이력 조회 최대 일수. */
        @Positive int maxHistoryDays
) {
}
