package com.potner.llm.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 텍스트 생성 모델 설정이다.
 *
 * <p>{@code apiKey} 가 비어 있으면 클라이언트가 no-op 으로 떨어진다. 기능을 끄는 플래그를 따로
 * 두지 않는 이유는 Firebase 발송과 같다. 임시 CI 컨테이너에 키가 없는데 초기화가 예외를 던지면
 * Health Check 가 실패해 배포가 막힌다.
 *
 * <p>기본 엔드포인트는 SSAFY GMS 다. OpenAI 호환이라 다른 호환 서비스로 옮길 때 이 값만 바꾸면
 * 된다. 라즈베리 수집기도 같은 프록시를 쓴다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.llm")
public record LlmProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String model,
        @Positive int maxOutputTokens,
        @Positive int connectTimeoutSeconds,
        @Positive int readTimeoutSeconds
) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
