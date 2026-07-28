package com.potner.push.application;

import com.potner.push.domain.FcmToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 자격증명이 없을 때 쓰는 발송기다. 아무것도 보내지 않고 기록만 남긴다.
 *
 * <p>로컬과 임시 CI 컨테이너가 이 구현으로 동작한다. 대상 조회와 문구 조립까지는 그대로 돌기
 * 때문에, 발송 직전까지의 경로는 자격증명 없이도 검증된다.
 */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public PushSendResult send(List<FcmToken> targets, PushMessage message) {
        log.info(
                "Push suppressed because Firebase credentials are not configured: "
                        + "targets={}, title={}, data={}",
                targets.size(),
                message.title(),
                message.data()
        );
        return PushSendResult.none();
    }
}
