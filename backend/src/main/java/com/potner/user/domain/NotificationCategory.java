package com.potner.user.domain;

/**
 * 알림 카테고리다. 발송 전에 사용자의 카테고리별 수신 토글을 확인하는 데 쓴다.
 *
 * <p>{@link UserNotificationSetting}의 세부 토글과 1:1로 대응한다.
 */
public enum NotificationCategory {

    /** 센서 이상, 개화, 케어 관련 알림. {@code plantCareEnabled}에 대응한다. */
    PLANT_CARE,

    /** 이벤트 및 공지사항. {@code marketingEnabled}에 대응하며 기본값이 꺼짐이다. */
    MARKETING
}
