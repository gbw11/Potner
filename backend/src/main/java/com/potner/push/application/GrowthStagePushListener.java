package com.potner.push.application;

import com.potner.push.config.PushConfiguration;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import com.potner.vision.application.GrowthStageAdvancedEvent;
import com.potner.vision.domain.DetectedGrowthStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 생장 단계가 올라간 것을 사용자 기기로 보낸다.
 *
 * <p>{@link BloomPushListener} 와 같은 이유로 {@code AFTER_COMMIT} 과 {@code @Async} 를 함께 쓴다.
 * 발행 스레드가 여기서는 사진 분석 스레드다. 동기로 두면 분석 풀이 Firebase 응답만큼 묶여
 * 다음 사진의 판정이 밀린다. 발송 실패가 이미 올라간 단계에 영향을 주면 안 되는 것도 그대로다.
 *
 * <p>푸시 풀({@code push-})과 분석 풀({@code vision-})이 다르므로 여기서 넘기면 실제로 스레드가
 * 갈린다. 같은 풀이면 {@code @Async} 를 붙여도 자기 큐로 되돌아가 격리가 되지 않는다.
 */
@Component
public class GrowthStagePushListener {

    private static final Logger log = LoggerFactory.getLogger(GrowthStagePushListener.class);

    private final PushTargetResolver pushTargetResolver;
    private final GrowthStagePushMessageFactory messageFactory;
    private final PushSender pushSender;
    private final FcmTokenService fcmTokenService;

    public GrowthStagePushListener(
            PushTargetResolver pushTargetResolver,
            GrowthStagePushMessageFactory messageFactory,
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
    public void onGrowthStageAdvanced(GrowthStageAdvancedEvent event) {
        // 개화 승급은 보내지 않는다. 같은 사진으로 개화 기록이 이미 "꽃이 피었어요" 를 보내므로
        // 여기서도 보내면 사용자가 알림을 두 번 받는다. 단계는 그대로 올라가고 알림만 양보한다.
        if (event.detectedStage() == DetectedGrowthStage.FLOWERING) {
            log.debug(
                    "Growth stage push skipped because the bloom record already notifies: plantId={}",
                    event.plantId()
            );
            return;
        }
        try {
            send(event);
        } catch (RuntimeException exception) {
            log.warn("Growth stage push failed: plantId={}", event.plantId(), exception);
        }
    }

    private void send(GrowthStageAdvancedEvent event) {
        // 생장 단계도 케어 알림이다. NotificationCategory.PLANT_CARE 의 설명이 케어 알림을 포함한다.
        List<FcmToken> targets = pushTargetResolver.resolve(
                event.userId(),
                NotificationCategory.PLANT_CARE
        );
        if (targets.isEmpty()) {
            log.debug(
                    "Growth stage push skipped because there is no target device: plantId={}",
                    event.plantId()
            );
            return;
        }

        PushSendResult result = pushSender.send(targets, messageFactory.create(event));
        int deactivated = fcmTokenService.deactivate(result.invalidInstallationIds());
        log.info(
                "Growth stage push handled: plantId={}, stage={}, targets={}, success={}, deactivated={}",
                event.plantId(),
                event.detectedStage(),
                targets.size(),
                result.successCount(),
                deactivated
        );
    }
}
