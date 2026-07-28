package com.potner.push.application;

import com.potner.alert.application.AlertOpenedEvent;
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
 * 새로 열린 이상 알림을 사용자 기기로 보낸다.
 *
 * <p>{@code AFTER_COMMIT}만으로는 부족하다. 그 단계의 리스너는 커밋을 끝낸 발행 스레드에서
 * 그대로 동기 실행되는데, 여기서 발행 스레드는 MQTT 콜백 스레드다. {@code @Async}를 함께
 * 붙여야 발송이 전용 풀로 넘어가고 센서 수집이 Firebase 응답을 기다리지 않는다.
 *
 * <p>커밋 이후에 도는 대신 트랜잭션과 영속성 컨텍스트가 없다. 그래서 이벤트가 엔티티가 아니라
 * 원시 값만 담고, 토큰 비활성처럼 쓰기가 필요한 작업은 트랜잭션을 가진 서비스에 위임한다.
 */
@Component
public class AlertPushListener {

    private static final Logger log = LoggerFactory.getLogger(AlertPushListener.class);

    private final PushTargetResolver pushTargetResolver;
    private final AlertPushMessageFactory messageFactory;
    private final PushSender pushSender;
    private final FcmTokenService fcmTokenService;

    public AlertPushListener(
            PushTargetResolver pushTargetResolver,
            AlertPushMessageFactory messageFactory,
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
    public void onAlertOpened(AlertOpenedEvent event) {
        try {
            send(event);
        } catch (RuntimeException exception) {
            // 발송 실패가 이미 저장된 알림에 영향을 주면 안 된다. 사용자는 알림 목록에서 확인한다.
            log.warn("Alert push failed: alertId={}", event.alertId(), exception);
        }
    }

    private void send(AlertOpenedEvent event) {
        List<FcmToken> targets = pushTargetResolver.resolve(
                event.userId(),
                NotificationCategory.PLANT_CARE
        );
        if (targets.isEmpty()) {
            log.debug("Alert push skipped because there is no target device: alertId={}", event.alertId());
            return;
        }

        PushSendResult result = pushSender.send(targets, messageFactory.create(event));
        int deactivated = fcmTokenService.deactivate(result.invalidInstallationIds());
        log.info(
                "Alert push handled: alertId={}, targets={}, success={}, deactivated={}",
                event.alertId(),
                targets.size(),
                result.successCount(),
                deactivated
        );
    }
}
