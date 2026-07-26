package com.potner.sensor.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneOffset;

/**
 * 센서 조회 정책이다.
 *
 * <p>측정값 저장과 응답은 UTC를 유지하고, 일·시간 구간 경계만 {@code zoneOffset} 기준으로 계산한다.
 * UTC 자정으로 하루를 끊으면 한국 시간 오전 9시에 날짜가 바뀌기 때문이다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.sensor")
public record SensorQueryProperties(
        @NotBlank @Pattern(regexp = "^(Z|[+-]\\d{2}:\\d{2})$") String zoneOffset,
        @Positive long freshnessThresholdMinutes,
        @Positive int maxHourIntervalDays,
        @Positive int maxDayIntervalDays
) {

    public long zoneOffsetSeconds() {
        return ZoneOffset.of(zoneOffset).getTotalSeconds();
    }
}
