package com.potner.push.application;

import com.potner.bloom.application.BloomRecordedEvent;
import com.potner.push.config.PushConfiguration;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 새로 남은 개화 기록을 사용자 기기로 보낸다.
 *
 * <p>{@link AlertPushListener} 와 같은 이유로 {@code AFTER_COMMIT} 과 {@code @Async} 를 함께
 * 쓴다. 발행 스레드가 여기서는 HTTP 요청 스레드라 동기로 두면 사용자가 Firebase 응답만큼
 * 기다린다. 발송 실패가 이미 저장된 기록에 영향을 주면 안 되는 것도 그대로다.
 */
@Component
public class BloomPushListener {

    private static final Logger log = LoggerFactory.getLogger(BloomPushListener.class);

    private final PushTargetResolver pushTargetResolver;
    private final BloomPushMessageFactory messageFactory;
    private final PushSender pushSender;
    private final FcmTokenService fcmTokenService;

    public BloomPushListener(
            PushTargetResolver pushTargetResolver,
            BloomPushMessageFactory messageFactory,
            PushSender pushSender,
            FcmTokenService fcmTokenService
    ) {
        this.pushTargetResolver = pushTargetResolver;
        this.messageFactory = messageFactory;
        this.pushSender = pushSender;
        this.fcmTokenService = fcmTokenService;
    }

    @Async(PushConfiguration.PUSH_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBloomRecorded(BloomRecordedEvent event) {
        try {
            send(event);
        } catch (RuntimeException exception) {
            log.warn("Bloom push failed: bloomId={}", event.bloomId(), exception);
        }
    }

    private void send(BloomRecordedEvent event) {
        // 개화도 케어 알림이다. NotificationCategory.PLANT_CARE 의 설명이 이미 개화를 포함한다.
        List<FcmToken> targets = pushTargetResolver.resolve(
                event.userId(),
                NotificationCategory.PLANT_CARE
        );
        if (targets.isEmpty()) {
            log.debug("Bloom push skipped because there is no target device: bloomId={}", event.bloomId());
            return;
        }

        PushSendResult result = pushSender.send(targets, messageFactory.create(event));
        int deactivated = fcmTokenService.deactivate(result.invalidInstallationIds());
        log.info(
                "Bloom push handled: bloomId={}, targets={}, success={}, deactivated={}",
                event.bloomId(),
                targets.size(),
                result.successCount(),
                deactivated
        );
    }
}
