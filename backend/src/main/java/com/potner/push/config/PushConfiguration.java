package com.potner.push.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.potner.push.application.FirebasePushSender;
import com.potner.push.application.LoggingPushSender;
import com.potner.push.application.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
@EnableAsync
public class PushConfiguration {

    /** {@code @Async}가 이름으로 executor 를 찾는다. 기본 executor 로 새면 격리가 사라진다. */
    public static final String PUSH_EXECUTOR = "pushTaskExecutor";

    private static final Logger log = LoggerFactory.getLogger(PushConfiguration.class);

    private static final String FIREBASE_APP_NAME = "potner-push";

    /**
     * 발송 전용 스레드 풀이다.
     *
     * <p>알림 생성은 MQTT 콜백 스레드 하나에서 순차로 돌기 때문에, 그 스레드에서 발송까지 하면
     * 센서 수집이 Firebase 응답을 기다리며 멈춘다. 발송을 여기로 넘겨서 떼어 놓는다.
     */
    @Bean(name = PUSH_EXECUTOR)
    public Executor pushTaskExecutor(PushProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("push-");
        // 큐가 넘치면 버린다. 푸시 한 건을 잃는 게 센서 수집을 멈추는 것보다 낫다.
        //
        // CallerRunsPolicy 는 발행 스레드에서 발송을 실행하므로 쓸 수 없고, 기본 AbortPolicy 도
        // 안 된다. 작업 제출은 커밋 직후 콜백에서 일어나므로 거기서 던진 예외가 발행 스레드로
        // 그대로 올라가 수집 루프를 깬다. 기록만 남기고 조용히 버리는 이유다.
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Alert push dropped because the push executor queue is full"));
        executor.initialize();
        return executor;
    }

    /**
     * 자격증명이 있으면 Firebase 로, 없으면 no-op 으로 보낸다.
     *
     * <p>경로가 설정됐는데 초기화가 실패해도 예외를 밖으로 내지 않는다. 여기서 던지면 컨텍스트
     * 기동이 실패해 푸시뿐 아니라 API 전체가 죽는다. 오류를 남기고 no-op 으로 내려간다.
     */
    @Bean
    public PushSender pushSender(PushProperties properties) {
        if (!properties.hasCredentials()) {
            log.info("Firebase credentials are not configured. Alert push runs as a no-op.");
            return new LoggingPushSender();
        }
        try {
            FirebasePushSender sender = new FirebasePushSender(
                    FirebaseMessaging.getInstance(firebaseApp(properties.credentialsPath()))
            );
            log.info("Firebase push sender initialized: credentialsPath={}", properties.credentialsPath());
            return sender;
        } catch (IOException | RuntimeException exception) {
            log.error(
                    "Firebase initialization failed. Alert push falls back to a no-op: credentialsPath={}",
                    properties.credentialsPath(),
                    exception
            );
            return new LoggingPushSender();
        }
    }

    /**
     * 이름 있는 앱으로 초기화한다.
     *
     * <p>기본 앱을 쓰면 같은 JVM 에서 두 번 초기화될 때 예외가 난다. 이름으로 찾아 재사용한다.
     */
    private FirebaseApp firebaseApp(String credentialsPath) throws IOException {
        FirebaseApp existing = FirebaseApp.getApps().stream()
                .filter(app -> FIREBASE_APP_NAME.equals(app.getName()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        try (InputStream credentials = Files.newInputStream(Path.of(credentialsPath))) {
            return FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(credentials))
                            .build(),
                    FIREBASE_APP_NAME
            );
        }
    }
}
