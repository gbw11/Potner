package com.potner.push.application;

import com.potner.location.application.StationWaterLowEvent;
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
 * 스테이션 물 부족을 사용자 기기로 보낸다.
 *
 * <p>{@link AlertPushListener} 와 같은 이유로 {@code AFTER_COMMIT} 과 {@code @Async} 를 함께
 * 쓴다. 발행 스레드가 여기서는 MQTT 콜백 스레드라, 동기로 두면 센서 수집이 Firebase 응답을
 * 기다리며 멈춘다.
 *
 * <p>이벤트가 부족으로 <strong>바뀔 때만</strong> 발행되므로 여기서 중복을 거를 필요가 없다.
 * 반복 보고 억제는 상태 전이를 아는 {@code StationWaterLowService} 의 일이다.
 */
@Component
public class StationWaterLowPushListener {

    private static final Logger log = LoggerFactory.getLogger(StationWaterLowPushListener.class);

    private final PushTargetResolver pushTargetResolver;
    private final StationWaterLowPushMessageFactory messageFactory;
    private final PushSender pushSender;
    private final FcmTokenService fcmTokenService;

    public StationWaterLowPushListener(
            PushTargetResolver pushTargetResolver,
            StationWaterLowPushMessageFactory messageFactory,
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
    public void onStationWaterLow(StationWaterLowEvent event) {
        try {
            send(event);
        } catch (RuntimeException exception) {
            log.warn("Station water-low push failed: robotId={}", event.robotId(), exception);
        }
    }

    private void send(StationWaterLowEvent event) {
        // 물 보충도 케어 알림이다. NotificationCategory.PLANT_CARE 의 설명이 케어 알림을 포함한다.
        List<FcmToken> targets = pushTargetResolver.resolve(
                event.userId(),
                NotificationCategory.PLANT_CARE
        );
        if (targets.isEmpty()) {
            log.debug(
                    "Station water-low push skipped because there is no target device: robotId={}",
                    event.robotId()
            );
            return;
        }

        PushSendResult result = pushSender.send(targets, messageFactory.create(event));
        int deactivated = fcmTokenService.deactivate(result.invalidInstallationIds());
        log.info(
                "Station water-low push handled: robotId={}, stationCode={}, targets={}, "
                        + "success={}, deactivated={}",
                event.robotId(),
                event.stationCode(),
                targets.size(),
                result.successCount(),
                deactivated
        );
    }
}
