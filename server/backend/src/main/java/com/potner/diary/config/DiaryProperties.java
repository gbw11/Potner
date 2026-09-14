package com.potner.diary.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 일기 생성 배치 설정이다.
 *
 * <p>{@code zone} 은 {@code @Scheduled} 가 {@code TimeZone.getTimeZone} 으로 해석하므로 맨
 * 오프셋('+09:00')을 받지 않는다. IANA 지역 ID 여야 하며, 잘못 넣으면 애플리케이션이 뜨지 않는다.
 * {@code potner.daily-light.zone} 과 같은 제약이다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.diary")
public record DiaryProperties(
        boolean enabled,
        @NotBlank String cron,
        @NotBlank String zone
) {
}
