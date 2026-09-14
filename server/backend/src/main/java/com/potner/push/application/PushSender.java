package com.potner.push.application;

import com.potner.push.domain.FcmToken;

import java.util.List;

/**
 * 실제 발송 수단이다.
 *
 * <p>자격증명 유무에 따라 구현이 갈린다. 기능을 끄는 플래그를 두지 않고 구현만 갈아끼우는 이유는
 * 임시 CI 컨테이너에 Firebase 키가 없기 때문이다. 초기화 실패로 기동이 막히면 배포가 멈춘다.
 *
 * <p>구현체는 수신 설정을 확인하지 않는다. 그 판정은 {@link PushTargetResolver}가 이미 끝냈다.
 */
public interface PushSender {

    PushSendResult send(List<FcmToken> targets, PushMessage message);
}
