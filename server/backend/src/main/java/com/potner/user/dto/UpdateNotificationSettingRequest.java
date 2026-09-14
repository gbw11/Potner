package com.potner.user.dto;

/**
 * 부분 수정 요청이다. 값을 보내지 않은 항목은 바뀌지 않는다.
 * boolean에 null을 설정하는 것은 의미가 없으므로 명시적 null과 미전송을 구분하지 않는다.
 */
public record UpdateNotificationSettingRequest(
        Boolean allEnabled,
        Boolean pushEnabled,
        Boolean plantCareEnabled,
        Boolean marketingEnabled
) {
}
