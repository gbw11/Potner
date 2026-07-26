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
        @NotNull @DecimalMin("0.0") @DecimalMax("0.5") BigDecimal hysteresisRatio
) {

    /**
     * 표본 수가 짝수면 중앙값이 두 값의 평균이 되어 스파이크 내성이 사라진다.
     */
    @AssertTrue(message = "potner.alert.sample-size must be an odd number")
    public boolean isSampleSizeOdd() {
        return sampleSize % 2 == 1;
    }
}
