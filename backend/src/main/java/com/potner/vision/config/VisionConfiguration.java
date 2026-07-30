package com.potner.vision.config;

import com.potner.vision.application.DisabledGrowthStageClassifier;
import com.potner.vision.application.GrowthStageClassifier;
import com.potner.vision.application.YoloGrowthStageClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VisionProperties.class)
public class VisionConfiguration {

    /** {@code @Async} 가 이름으로 executor 를 찾는다. 기본 executor 로 새면 격리가 사라진다. */
    public static final String VISION_EXECUTOR = "visionTaskExecutor";

    /**
     * 추론 컨테이너의 {@code YOLO_MAX_CONCURRENCY} 와 같아야 한다.
     *
     * <p>독립적인 조정 값이 아니라 저쪽 설정에서 따라온 값이다. 여기를 늘려도 추론이 한 번에
     * 하나만 처리하므로 요청이 저쪽에서 줄을 서고, 그러면 read timeout 이 정상 대기를 실패로
     * 만든다. 저쪽을 늘릴 때 함께 늘린다.
     */
    private static final int INFERENCE_CONCURRENCY = 1;

    /**
     * 대기 상한이다. 촬영이 기기당 하루 한 장이라 정상 운영에서는 큐가 쌓이지 않는다.
     * 추론이 멈춘 동안 들어온 업로드를 흘려보내지 않을 만큼만 잡는다.
     */
    private static final int ANALYSIS_QUEUE_CAPACITY = 50;

    private static final Logger log = LoggerFactory.getLogger(VisionConfiguration.class);

    /**
     * 사진 분석 전용 스레드 풀이다.
     *
     * <p>푸시 풀과 나눠 둔다. 추론은 CPU 로 수 초가 걸리는데 같은 풀을 쓰면 분석이 도는 동안
     * 알림 발송이 큐에서 기다린다. 알림은 사용자가 즉시 보는 것이고 분석은 늦어도 무해하다.
     */
    @Bean(name = VISION_EXECUTOR)
    public Executor visionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(INFERENCE_CONCURRENCY);
        executor.setMaxPoolSize(INFERENCE_CONCURRENCY);
        executor.setQueueCapacity(ANALYSIS_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("vision-");
        // 큐가 넘치면 버린다. 사진은 이미 저장됐고 판정은 나중에 다시 할 수 있다.
        //
        // 작업 제출이 커밋 직후 콜백에서 일어나므로 기본 AbortPolicy 는 쓸 수 없다. 거기서 던진
        // 예외가 업로드 스레드로 올라가면 이미 커밋된 사진에 대해 장치가 실패를 받는다.
        // CallerRunsPolicy 도 안 된다. 업로드 스레드에서 추론을 돌리게 된다.
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Photo growth analysis dropped because the vision executor queue is full"));
        executor.initialize();
        return executor;
    }

    /**
     * 플래그가 켜져 있으면 추론 서비스로, 꺼져 있으면 no-op 으로 보낸다.
     *
     * <p>어느 쪽이 선택됐는지는 이 로그가 유일한 신호다. 서비스가 떠 있지 않아도 기동은 성공하고
     * 첫 호출에서야 실패하므로, 실제로 붙는지는 분석 로그까지 봐야 한다. {@code LlmConfiguration}
     * 과 같은 방침이다.
     */
    @Bean
    public GrowthStageClassifier growthStageClassifier(
            VisionProperties properties,
            ObjectMapper objectMapper
    ) {
        if (!properties.enabled()) {
            log.info("Growth stage inference is disabled. Photo analysis runs as a no-op.");
            return new DisabledGrowthStageClassifier();
        }

        log.info(
                "Growth stage classifier initialized: baseUrl={}, requestConfidence={}, imageSize={}, "
                        + "minConfidence={}, autoAdvanceEnabled={}",
                properties.baseUrl(),
                properties.requestConfidence(),
                properties.imageSize(),
                properties.minConfidence(),
                properties.autoAdvanceEnabled()
        );
        return new YoloGrowthStageClassifier(
                restClient(properties),
                objectMapper,
                properties.requestConfidence(),
                properties.imageSize()
        );
    }

    /**
     * 타임아웃을 반드시 건다.
     *
     * <p>기본값은 무제한이다. 추론은 {@code YOLO_MAX_CONCURRENCY=1} 이라 앞선 요청이 있으면 그만큼
     * 더 기다리는데, 무제한이면 분석 스레드가 그대로 묶여 다음 사진이 처리되지 않는다.
     */
    private RestClient restClient(VisionProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
