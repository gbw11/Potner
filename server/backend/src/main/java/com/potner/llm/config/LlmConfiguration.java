package com.potner.llm.config;

import com.potner.llm.application.DisabledLlmClient;
import com.potner.llm.application.GmsChatClient;
import com.potner.llm.application.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    /**
     * 키가 있으면 GMS 로, 없으면 no-op 으로 보낸다.
     *
     * <p>어느 쪽이 선택됐는지는 이 로그가 유일한 신호다. 키가 잘못돼도 기동은 성공하고 호출
     * 시점에야 실패하므로, 실제로 붙는지는 첫 호출 로그까지 봐야 한다.
     */
    @Bean
    public LlmClient llmClient(LlmProperties properties) {
        if (!properties.hasApiKey()) {
            log.info("LLM API key is not configured. Text generation runs as a no-op.");
            return new DisabledLlmClient();
        }

        log.info(
                "LLM client initialized: baseUrl={}, model={}, maxOutputTokens={}",
                properties.baseUrl(),
                properties.model(),
                properties.maxOutputTokens()
        );
        return new GmsChatClient(
                restClient(properties),
                properties.model(),
                properties.maxOutputTokens()
        );
    }

    /**
     * 타임아웃을 반드시 건다.
     *
     * <p>기본값은 무제한이라 모델이 응답하지 않으면 배치 스레드가 그대로 묶인다. 하루 한 번
     * 도는 배치가 다음 날까지 살아 있으면 스케줄러가 겹친다.
     */
    private RestClient restClient(LlmProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
