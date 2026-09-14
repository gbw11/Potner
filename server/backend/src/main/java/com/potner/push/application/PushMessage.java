package com.potner.push.application;

import java.util.Map;

/**
 * 기기로 보낼 알림 한 건이다.
 *
 * <p>문구를 서버가 만들어 넣는다. 앱이 백그라운드일 때 {@code data}만 있는 메시지로는 시스템
 * 알림을 띄울 수 없어서, 표시 문구를 클라이언트가 조립하는 다른 응답들과 규칙이 다르다.
 */
public record PushMessage(String title, String body, Map<String, String> data) {
}
