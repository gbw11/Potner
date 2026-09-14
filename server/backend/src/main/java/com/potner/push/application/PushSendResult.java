package com.potner.push.application;

import java.util.List;

/**
 * 발송 결과다.
 *
 * <p>{@code invalidInstallationIds}는 FCM 이 영구 무효라고 답한 기기다. 일시적인 오류는 담지
 * 않는다. 브로커 장애로 실패한 토큰까지 비활성으로 내리면 멀쩡한 기기가 조용히 푸시를 못 받는다.
 */
public record PushSendResult(int successCount, List<String> invalidInstallationIds) {

    public static PushSendResult none() {
        return new PushSendResult(0, List.of());
    }
}
