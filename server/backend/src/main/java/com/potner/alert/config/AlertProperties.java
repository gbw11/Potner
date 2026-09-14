package com.potner.alert.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 센서 이상 판정 정책이다.
 *
 * <p>{@code sampleSize}는 순간 스파이크를 지우기 위한 중앙값 표본 수이고,
 * {@code hysteresisRatio}는 기준선 근처에서 값이 왕복할 때 알림이 반복되는 것을 막는 복귀 여유값 비율이다.
 * 두 장치는 서로 다른 문제를 막으므로 함께 필요하다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.alert")
public record AlertProperties(
        @Positive int sampleSize,
        @NotNull @DecimalMin("0.0") @DecimalMax("0.5") BigDecimal hysteresisRatio,
        @Positive int maxPageSize,

        /**
         * 배수트레이 알림 임계값 = 식물별 권장 급수량 × 이 배수.
         *
         * <p>고정 ml 이 아닌 이유는 트레이 용량이 화분 크기에 따라 다르기 때문이다. 권장 급수량이
         * 화분 크기의 대리 지표라서 그 값에 곱한다. 사실상 "급수 몇 회마다 비우기" 라서 실측 후
         * 조정하기 쉽다.
         *
         * <p>시연에서 즉석으로 보여주려면 1 로 낮춘다 — 급수 한 번에 알림이 뜬다.
         */
        @Positive int drainageTrayWateringMultiplier
) {

    /**
     * 표본 수가 짝수면 중앙값이 두 값의 평균이 되어 스파이크 내성이 사라진다.
     */
    @AssertTrue(message = "potner.alert.sample-size must be an odd number")
    public boolean isSampleSizeOdd() {
        return sampleSize % 2 == 1;
    }
}
