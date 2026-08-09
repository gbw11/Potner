package com.potner.push.application;

import com.potner.push.config.PushConfiguration;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import com.potner.vision.application.PlantSproutedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 새싹 판정을 사용자 기기로 보낸다.
 *
 * <p>{@link GrowthStagePushListener} 와 같은 이유로 {@code AFTER_COMMIT} 과 {@code @Async} 를
 * 함께 쓴다. 발행 스레드가 여기서는 사진 분석 스레드다. 동기로 두면 분석 풀이 Firebase 응답만큼
 * 묶여 다음 사진의 판정이 밀린다. 발송 실패가 이미 남은 판정에 영향을 주면 안 되는 것도 그대로다.
 */
@Component
public class SproutPushListener {

    private static final Logger log = LoggerFactory.getLogger(SproutPushListener.class);

    private final PushTargetResolver pushTargetResolver;
    private final SproutPushMessageFactory messageFactory;
    private final PushSender pushSender;
    private final FcmTokenService fcmTokenService;

    public SproutPushListener(
            PushTargetResolver pushTargetResolver,
            SproutPushMessageFactory messageFactory,
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
    public void onPlantSprouted(PlantSproutedEvent event) {
        try {
            send(event);
        } catch (RuntimeException exception) {
            log.warn("Sprout push failed: plantId={}", event.plantId(), exception);
        }
    }

    private void send(PlantSproutedEvent event) {
        // 새싹도 케어 알림이다. NotificationCategory.PLANT_CARE 의 설명이 성장 소식을 포함한다.
        List<FcmToken> targets = pushTargetResolver.resolve(
                event.userId(),
                NotificationCategory.PLANT_CARE
        );
        if (targets.isEmpty()) {
            log.debug("Sprout push skipped because there is no target device: plantId={}", event.plantId());
            return;
        }

        PushSendResult result = pushSender.send(targets, messageFactory.create(event));
        int deactivated = fcmTokenService.deactivate(result.invalidInstallationIds());
        log.info(
                "Sprout push handled: plantId={}, targets={}, success={}, deactivated={}",
                event.plantId(),
                targets.size(),
                result.successCount(),
                deactivated
        );
    }
}
