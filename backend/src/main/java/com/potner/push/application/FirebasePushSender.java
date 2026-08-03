package com.potner.push.application;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.potner.push.domain.FcmToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Firebase Cloud Messaging 으로 발송한다.
 *
 * <p>토큰 하나가 실패해도 나머지는 계속 보낸다. 무효 토큰은 목록으로 돌려주고 비활성 처리는
 * 호출자가 한다. 발송기가 직접 DB 를 건드리지 않아야 트랜잭션 밖에서 안전하게 돌 수 있다.
 */
public class FirebasePushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushSender.class);

    /** FCM 이 한 요청에 받는 메시지 수 상한이다. 넘겨서 호출하면 예외가 난다. */
    private static final int MAX_MESSAGES_PER_REQUEST = 500;

    private final FirebaseMessaging messaging;

    public FirebasePushSender(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    @Override
    public PushSendResult send(List<FcmToken> targets, PushMessage message) {
        int successCount = 0;
        List<String> invalidInstallationIds = new ArrayList<>();
        for (int from = 0; from < targets.size(); from += MAX_MESSAGES_PER_REQUEST) {
            int to = Math.min(from + MAX_MESSAGES_PER_REQUEST, targets.size());
            successCount += sendChunk(targets.subList(from, to), message, invalidInstallationIds);
        }
        return new PushSendResult(successCount, List.copyOf(invalidInstallationIds));
    }

    private int sendChunk(
            List<FcmToken> chunk,
            PushMessage message,
            List<String> invalidInstallationIds
    ) {
        BatchResponse response;
        try {
            response = messaging.sendEach(chunk.stream().map(target -> toMessage(target, message)).toList());
        } catch (FirebaseMessagingException exception) {
            // 요청 자체가 실패했다. 개별 토큰의 유효성은 알 수 없으므로 아무것도 비활성하지 않는다.
            log.warn("Push request failed: targets={}", chunk.size(), exception);
            return 0;
        }

        List<SendResponse> responses = response.getResponses();
        for (int index = 0; index < responses.size(); index++) {
            SendResponse each = responses.get(index);
            if (each.isSuccessful()) {
                continue;
            }
            String installationId = chunk.get(index).getInstallationId();
            FirebaseMessagingException exception = each.getException();
            if (isPermanentlyInvalid(exception)) {
                invalidInstallationIds.add(installationId);
            } else {
                log.warn(
                        "Push failed for a device but the token is kept: installationId={}, errorCode={}",
                        installationId,
                        exception == null ? null : exception.getMessagingErrorCode()
                );
            }
        }
        return response.getSuccessCount();
    }

    /**
     * 토큰을 영구히 못 쓰는 오류인지 판정한다.
     *
     * <p>{@code UNAVAILABLE}·{@code INTERNAL}·{@code QUOTA_EXCEEDED}는 재시도하면 성공할 수 있는
     * 일시 오류다. 이걸 무효로 취급하면 브로커 장애 한 번에 멀쩡한 기기의 등록이 전부 꺼진다.
     */
    static boolean isPermanentlyInvalid(FirebaseMessagingException exception) {
        if (exception == null) {
            return false;
        }
        MessagingErrorCode errorCode = exception.getMessagingErrorCode();
        return errorCode == MessagingErrorCode.UNREGISTERED
                || errorCode == MessagingErrorCode.INVALID_ARGUMENT;
    }

    /**
     * 안드로이드 알림 채널이다. 앱의 {@code NotificationChannels.ALERTS} 와 Manifest 의
     * {@code default_notification_channel_id} 가 같은 문자열이어야 한다.
     *
     * <p>이 값을 지정하지 않으면 FCM 이 자동 생성한 기본 채널로 들어간다. 그 채널은
     * {@code IMPORTANCE_DEFAULT} 라 알림이 도착해도 상태바에만 쌓이고 헤드업 팝업도 소리도
     * 나지 않는다. 앱이 만든 채널은 {@code IMPORTANCE_HIGH} 다.
     */
    private static final String ANDROID_CHANNEL_ID = "potner_alerts";

    private Message toMessage(FcmToken target, PushMessage message) {
        return Message.builder()
                .setToken(target.getToken())
                .setNotification(Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build())
                .setAndroidConfig(androidConfig())
                .putAllData(message.data())
                .build();
    }

    /**
     * <p>{@code Priority.HIGH} 는 절전(Doze) 중인 기기를 깨워 즉시 전달한다. 기본값
     * ({@code NORMAL})이면 화면을 켤 때까지, 길게는 수십 분까지 묶여 있을 수 있다. 물 부족·
     * 이상 알림은 지금 조치해야 하는 것이라 늦게 도착하면 의미가 없다.
     *
     * <p>배터리 비용이 있으므로 남용할 것은 아니지만, 이 서버가 보내는 알림은 모두 사용자가
     * 손을 써야 하는 것뿐이다. 마케팅 알림이 생기면 그때 종류별로 갈라야 한다.
     */
    private AndroidConfig androidConfig() {
        return AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(AndroidNotification.builder()
                        .setChannelId(ANDROID_CHANNEL_ID)
                        .build())
                .build();
    }
}
