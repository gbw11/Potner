package com.potner.push.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.push.domain.FcmTokenRepository;
import com.potner.push.domain.PushPlatform;
import com.potner.push.dto.RegisterFcmTokenRequest;
import com.potner.user.domain.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FcmTokenServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String INSTALLATION_ID = "fid-abcdefghijklmnopqrstuv";
    private static final Instant NOW = Instant.parse("2026-07-27T05:30:00Z");

    @Mock
    private FcmTokenRepository tokenRepository;

    @Mock
    private AppUserRepository appUserRepository;

    private FcmTokenService fcmTokenService;

    @BeforeEach
    void setUp() {
        fcmTokenService = new FcmTokenService(
                tokenRepository,
                appUserRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void registerUpsertsTrimmedValuesWithUtcTimestamp() {
        givenExistingUser();

        fcmTokenService.register(
                USER_ID,
                "  " + INSTALLATION_ID + "  ",
                new RegisterFcmTokenRequest("  fcm-token-value  ", PushPlatform.ANDROID)
        );

        verify(tokenRepository).upsert(
                INSTALLATION_ID,
                USER_ID,
                "fcm-token-value",
                "ANDROID",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void unregisterIsScopedToTheOwner() {
        givenExistingUser();

        fcmTokenService.unregister(USER_ID, INSTALLATION_ID);

        // 설치 ID 만으로 지우면 남의 기기 등록을 끊을 수 있다.
        verify(tokenRepository).deleteOwned(USER_ID, INSTALLATION_ID);
    }

    @Test
    void registerRejectsMissingUser() {
        when(appUserRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> fcmTokenService.register(
                USER_ID,
                INSTALLATION_ID,
                new RegisterFcmTokenRequest("fcm-token-value", PushPlatform.ANDROID)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(tokenRepository, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    void unregisterRejectsMissingUser() {
        when(appUserRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> fcmTokenService.unregister(USER_ID, INSTALLATION_ID))
                .isInstanceOf(BusinessException.class);

        verify(tokenRepository, never()).deleteOwned(anyString(), anyString());
    }

    @Test
    void rejectsBlankInstallationId() {
        givenExistingUser();

        assertThatThrownBy(() -> fcmTokenService.register(
                USER_ID,
                "   ",
                new RegisterFcmTokenRequest("fcm-token-value", PushPlatform.ANDROID)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsInstallationIdLongerThanColumn() {
        givenExistingUser();
        String tooLong = "f".repeat(129);

        assertThatThrownBy(() -> fcmTokenService.register(
                USER_ID,
                tooLong,
                new RegisterFcmTokenRequest("fcm-token-value", PushPlatform.ANDROID)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(tokenRepository, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    void acceptsInstallationIdAtColumnLimit() {
        givenExistingUser();
        String atLimit = "f".repeat(128);

        fcmTokenService.register(
                USER_ID,
                atLimit,
                new RegisterFcmTokenRequest("fcm-token-value", PushPlatform.ANDROID)
        );

        verify(tokenRepository).upsert(
                atLimit,
                USER_ID,
                "fcm-token-value",
                "ANDROID",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void iosPlatformIsAccepted() {
        givenExistingUser();

        fcmTokenService.register(
                USER_ID,
                INSTALLATION_ID,
                new RegisterFcmTokenRequest("fcm-token-value", PushPlatform.IOS)
        );

        verify(tokenRepository).upsert(
                INSTALLATION_ID,
                USER_ID,
                "fcm-token-value",
                "IOS",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    private void givenExistingUser() {
        when(appUserRepository.existsById(USER_ID)).thenReturn(true);
    }
}
