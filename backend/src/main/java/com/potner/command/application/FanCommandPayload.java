package com.potner.command.application;

/**
 * 송풍 명령 페이로드다. 라즈베리가 팬을 {@code seconds} 초 동안 돌리고 결과를 회신한다.
 *
 * <p>가동 시간을 서버가 정한다. 토양 수분이 얼마나 과다한지는 서버만 알고, 말리기는 짧은
 * 가동을 반복하며 수분을 다시 재는 쪽이 과건조를 막는다.
 */
public record FanCommandPayload(Integer seconds, String requestId) {
}
