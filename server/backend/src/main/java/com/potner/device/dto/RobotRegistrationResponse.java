package com.potner.device.dto;

/**
 * 로봇 등록 결과다.
 *
 * <p>{@code uploadToken} 원문은 <strong>이 응답에서만</strong> 볼 수 있다. 서버는 해시만
 * 저장하므로 다시 조회할 수 없다. 앱은 사용자가 라즈베리 설정에 넣을 수 있도록 안내해야 하며,
 * 잃어버리면 재발급해야 한다.
 */
public record RobotRegistrationResponse(
        RegisteredRobotResponse robot,
        String uploadToken
) {
}
