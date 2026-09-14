package com.potner.user.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserNotificationSettingTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";

    @Test
    void defaultsAllowPlantCareButNotMarketing() {
        UserNotificationSetting setting = UserNotificationSetting.defaults(USER_ID);

        assertThat(setting.allowsPush(NotificationCategory.PLANT_CARE)).isTrue();
        // 회원가입의 선택 동의 항목이라 켜진 상태로 시작하지 않는다.
        assertThat(setting.allowsPush(NotificationCategory.MARKETING)).isFalse();
    }

    @Test
    void masterSwitchBlocksEveryCategoryEvenWhenDetailTogglesAreOn() {
        UserNotificationSetting setting = UserNotificationSetting.defaults(USER_ID);
        setting.apply(false, true, true, true);

        assertThat(setting.allowsPush(NotificationCategory.PLANT_CARE)).isFalse();
        assertThat(setting.allowsPush(NotificationCategory.MARKETING)).isFalse();
    }

    @Test
    void pushToggleBlocksEveryCategoryEvenWhenMasterIsOn() {
        UserNotificationSetting setting = UserNotificationSetting.defaults(USER_ID);
        setting.apply(true, false, true, true);

        assertThat(setting.allowsPush(NotificationCategory.PLANT_CARE)).isFalse();
        assertThat(setting.allowsPush(NotificationCategory.MARKETING)).isFalse();
    }

    @Test
    void categoryTogglesAreIndependent() {
        UserNotificationSetting setting = UserNotificationSetting.defaults(USER_ID);
        setting.apply(true, true, false, true);

        assertThat(setting.allowsPush(NotificationCategory.PLANT_CARE)).isFalse();
        assertThat(setting.allowsPush(NotificationCategory.MARKETING)).isTrue();
    }
}
