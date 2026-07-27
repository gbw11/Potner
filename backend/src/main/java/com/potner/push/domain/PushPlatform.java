package com.potner.push.domain;

/**
 * 등록 기기의 플랫폼이다.
 *
 * <p>앱은 android 와 iOS 가 아니면 등록 자체를 건너뛰므로 그 밖의 값은 서버에 도달하지 않는다.
 * 그래서 두 값만 두고, 알 수 없는 값이 오면 요청 본문 역직렬화 단계에서 400으로 막힌다.
 */
public enum PushPlatform {
    ANDROID,
    IOS
}
