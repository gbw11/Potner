package com.potner.command.application;

/**
 * 촬영 명령 페이로드다. 라즈베리 수신기는 {@code requestId} 만 요구하고 그 외 필드는
 * 촬영 옵션 확장 여지로 무시한다.
 */
public record CaptureCommandPayload(String requestId) {
}
