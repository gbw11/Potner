package com.potner.user.dto;

import com.potner.user.domain.UserNotificationSetting;

public record NotificationSettingResponse(
        boolean allEnabled,
        boolean pushEnabled,
        boolean plantCareEnabled,
        boolean marketingEnabled
) {
    public static NotificationSettingResponse from(UserNotificationSetting setting) {
        return new NotificationSettingResponse(
                setting.isAllEnabled(),
                setting.isPushEnabled(),
                setting.isPlantCareEnabled(),
                setting.isMarketingEnabled()
        );
    }
}
