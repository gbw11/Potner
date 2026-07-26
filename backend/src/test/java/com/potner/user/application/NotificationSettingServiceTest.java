package com.potner.user.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.user.domain.AppUserRepository;
import com.potner.user.domain.UserNotificationSetting;
import com.potner.user.domain.UserNotificationSettingRepository;
import com.potner.user.dto.NotificationSettingResponse;
import com.potner.user.dto.UpdateNotificationSettingRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSettingServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";

    @Mock
    private UserNotificationSettingRepository settingRepository;

    @Mock
    private AppUserRepository appUserRepository;

    private NotificationSettingService notificationSettingService;

    @BeforeEach
    void setUp() {
        notificationSettingService =
                new NotificationSettingService(settingRepository, appUserRepository);
    }

    @Test
    void userWithoutStoredRowGetsDefaultsWithoutCreatingOne() {
        givenExistingUser();
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.empty());

        NotificationSettingResponse response = notificationSettingService.getMySettings(USER_ID);

        assertThat(response.allEnabled()).isTrue();
        assertThat(response.pushEnabled()).isTrue();
        assertThat(response.plantCareEnabled()).isTrue();
        // 회원가입의 선택 동의 항목이라 켜진 상태로 시작하지 않는다.
        assertThat(response.marketingEnabled()).isFalse();
        verify(settingRepository, never()).save(any());
    }

    @Test
    void missingUserIsRejected() {
        when(appUserRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> notificationSettingService.getMySettings(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void firstUpdateStoresRowWithRemainingDefaultsKept() {
        givenExistingUser();
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.empty());
        givenSaveReturnsArgument();

        NotificationSettingResponse response = notificationSettingService.updateMySettings(
                USER_ID,
                new UpdateNotificationSettingRequest(null, null, null, true)
        );

        assertThat(response.marketingEnabled()).isTrue();
        assertThat(response.allEnabled()).isTrue();
        assertThat(response.pushEnabled()).isTrue();
        assertThat(response.plantCareEnabled()).isTrue();

        ArgumentCaptor<UserNotificationSetting> captor =
                ArgumentCaptor.forClass(UserNotificationSetting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void onlyProvidedFieldsChange() {
        givenExistingUser();
        UserNotificationSetting stored = UserNotificationSetting.defaults(USER_ID);
        stored.apply(true, true, true, true);
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        givenSaveReturnsArgument();

        NotificationSettingResponse response = notificationSettingService.updateMySettings(
                USER_ID,
                new UpdateNotificationSettingRequest(null, false, null, null)
        );

        assertThat(response.pushEnabled()).isFalse();
        assertThat(response.allEnabled()).isTrue();
        assertThat(response.plantCareEnabled()).isTrue();
        assertThat(response.marketingEnabled()).isTrue();
    }

    @Test
    void emptyRequestKeepsEverything() {
        givenExistingUser();
        UserNotificationSetting stored = UserNotificationSetting.defaults(USER_ID);
        when(settingRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        givenSaveReturnsArgument();

        NotificationSettingResponse response = notificationSettingService.updateMySettings(
                USER_ID,
                new UpdateNotificationSettingRequest(null, null, null, null)
        );

        assertThat(response.allEnabled()).isTrue();
        assertThat(response.marketingEnabled()).isFalse();
    }

    private void givenExistingUser() {
        when(appUserRepository.existsById(USER_ID)).thenReturn(true);
    }

    private void givenSaveReturnsArgument() {
        when(settingRepository.save(any(UserNotificationSetting.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
