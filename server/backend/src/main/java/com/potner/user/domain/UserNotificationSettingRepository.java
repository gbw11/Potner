package com.potner.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationSettingRepository
        extends JpaRepository<UserNotificationSetting, String> {
}
