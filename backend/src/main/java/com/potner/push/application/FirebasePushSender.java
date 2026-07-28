package com.potner.push.application;

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

    private Message toMessage(FcmToken target, PushMessage message) {
        return Message.builder()
                .setToken(target.getToken())
                .setNotification(Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build())
                .putAllData(message.data())
                .build();
    }
}
