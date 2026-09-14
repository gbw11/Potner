package com.potner.push.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 푸시 발송 설정이다.
 *
 * <p>{@code credentialsPath}가 비어 있으면 발송기가 no-op 으로 떨어진다. 기능을 끄는 플래그를
 * 따로 두지 않는다. 임시 CI 컨테이너에는 Firebase 키가 없는데 초기화가 예외를 던지면 Health
 * Check 가 실패해 배포가 막히기 때문이다. 구현만 갈아끼우고 발송 경로는 항상 살려 둔다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.push")
public record PushProperties(
        String credentialsPath,
        @Positive int corePoolSize,
        @Positive int maxPoolSize,
        @Positive int queueCapacity
) {

    public boolean hasCredentials() {
        return credentialsPath != null && !credentialsPath.isBlank();
    }

    @AssertTrue(message = "potner.push.max-pool-size must not be smaller than core-pool-size")
    public boolean isPoolSizeConsistent() {
        return maxPoolSize >= corePoolSize;
    }
}
