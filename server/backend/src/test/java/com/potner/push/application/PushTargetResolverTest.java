package com.potner.push.application;

import com.potner.push.domain.FcmToken;
import com.potner.push.domain.FcmTokenRepository;
import com.potner.user.domain.NotificationCategory;
import com.potner.user.domain.UserNotificationSetting;
import com.potner.user.domain.UserNotificationSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushTargetResolverTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";

    @Mock
    private UserNotificationSettingRepository settingRepository;

    @Mock
    private FcmTokenRepository tokenRepository;

    private PushTargetResolver pushTargetResolver;

    @BeforeEach
    void setUp() {
        pushTargetResolver = new PushTargetResolver(settingRepository, tokenRepository);
    }

    @Test
    void userWithoutStoredSettingIsTreatedAsDefaults() {
        FcmToken token = mock(FcmToken.class);
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.findAllByUserIdAndActiveIsTrue(USER_ID)).thenReturn(List.of(token));

        List<FcmToken> targets = pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE);

        assertThat(targets).containsExactly(token);
        // 조회가 설정 행을 만들지 않아야 한다.
        verify(settingRepository, never()).save(any());
    }

    @Test
    void marketingIsBlockedForUserWithoutStoredSetting() {
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.empty());

        List<FcmToken> targets = pushTargetResolver.resolve(USER_ID, NotificationCategory.MARKETING);

        assertThat(targets).isEmpty();
        // 판정에서 막혔으면 토큰을 조회할 이유가 없다.
        verify(tokenRepository, never()).findAllByUserIdAndActiveIsTrue(any());
    }

    @Test
    void masterSwitchOffReturnsNoTargets() {
        UserNotificationSetting setting = UserNotificationSetting.defaults(USER_ID);
        setting.apply(false, true, true, true);
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.of(setting));

        List<FcmToken> targets = pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE);

        assertThat(targets).isEmpty();
        verify(tokenRepository, never()).findAllByUserIdAndActiveIsTrue(any());
    }

    @Test
    void categoryToggleOffReturnsNoTargets() {
        UserNotificationSetting setting = UserNotificationSetting.defaults(USER_ID);
        setting.apply(true, true, false, null);
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.of(setting));

        List<FcmToken> targets = pushTargetResolver.resolve(USER_ID, NotificationCategory.PLANT_CARE);

        assertThat(targets).isEmpty();
    }

    @Test
    void enabledMarketingReturnsTargets() {
        UserNotificationSetting setting = UserNotificationSetting.defaults(USER_ID);
        setting.apply(null, null, null, true);
        FcmToken token = mock(FcmToken.class);
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.of(setting));
        when(tokenRepository.findAllByUserIdAndActiveIsTrue(USER_ID)).thenReturn(List.of(token));

        List<FcmToken> targets = pushTargetResolver.resolve(USER_ID, NotificationCategory.MARKETING);

        assertThat(targets).containsExactly(token);
    }
}
