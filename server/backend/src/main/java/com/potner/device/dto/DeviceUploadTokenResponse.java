package com.potner.device.dto;

/**
 * 업로드 토큰 재발급 결과다. 재발급 즉시 이전 토큰은 무효가 되므로 라즈베리 설정도 함께 바꿔야 한다.
 * 원문은 이 응답에서만 볼 수 있다.
 */
public record DeviceUploadTokenResponse(String robotId, String uploadToken) {
}
