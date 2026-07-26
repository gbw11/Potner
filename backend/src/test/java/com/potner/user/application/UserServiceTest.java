package com.potner.user.application;

import com.potner.auth.domain.RefreshTokenRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.security.AuthenticatedUser;
import com.potner.user.domain.AccountStatus;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import com.potner.user.dto.ChangePasswordRequest;
import com.potner.user.dto.UpdateNicknameRequest;
import com.potner.user.dto.UserMeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T00:40:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 7, 26, 0, 40);
    private static final String CURRENT_HASH = "$2a$10$current";
    private static final String NEW_HASH = "$2a$10$new";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                appUserRepository,
                refreshTokenRepository,
                passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void nicknameIsTrimmedWhenChanged() {
        AppUser user = givenActiveUser();

        UserMeResponse response = userService.changeNickname(
                user.getId(),
                new UpdateNicknameRequest("  새 닉네임  ")
        );

        assertThat(response.nickname()).isEqualTo("새 닉네임");
        assertThat(user.getNickname()).isEqualTo("새 닉네임");
    }

    @Test
    void inactiveAccountCannotUseAccountApis() {
        AppUser user = givenActiveUser();
        user.suspend();

        assertThatThrownBy(() -> userService.getMe(new AuthenticatedUser(user.getId())))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_INACTIVE);
    }

    @Test
    void wrongCurrentPasswordIsRejectedWithoutRevokingTokens() {
        AppUser user = givenActiveUser();
        when(passwordEncoder.matches("wrong-password1", CURRENT_HASH)).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(
                user.getId(),
                new ChangePasswordRequest("wrong-password1", "new-password1")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PASSWORD_MISMATCH);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyString(), any());
    }

    @Test
    void reusingTheSamePasswordIsRejected() {
        AppUser user = givenActiveUser();
        when(passwordEncoder.matches("password1", CURRENT_HASH)).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(
                user.getId(),
                new ChangePasswordRequest("password1", "password1")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PASSWORD_UNCHANGED);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyString(), any());
    }

    @Test
    void socialAccountWithoutPasswordCannotChangePassword() {
        AppUser user = AppUser.createEmailUser("social@example.com", "소셜", null);
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.changePassword(
                user.getId(),
                new ChangePasswordRequest("password1", "new-password1")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PASSWORD_MISMATCH);
    }

    @Test
    void successfulPasswordChangeRevokesEveryRefreshToken() {
        AppUser user = givenActiveUser();
        when(passwordEncoder.matches("password1", CURRENT_HASH)).thenReturn(true);
        when(passwordEncoder.matches("new-password1", CURRENT_HASH)).thenReturn(false);
        when(passwordEncoder.encode("new-password1")).thenReturn(NEW_HASH);

        userService.changePassword(
                user.getId(),
                new ChangePasswordRequest("password1", "new-password1")
        );

        assertThat(user.getPasswordHash()).isEqualTo(NEW_HASH);
        // 폐기하지 않으면 유출된 Refresh Token 이 계속 유효해 변경의 목적이 반감된다.
        verify(refreshTokenRepository).revokeAllByUserId(user.getId(), NOW_UTC);
    }

    @Test
    void withdrawMarksAccountAndRevokesEveryRefreshToken() {
        AppUser user = givenActiveUser();

        userService.withdraw(user.getId());

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
        assertThat(user.isActive()).isFalse();
        verify(refreshTokenRepository).revokeAllByUserId(user.getId(), NOW_UTC);
    }

    @Test
    void withdrawingTwiceIsRejected() {
        AppUser user = givenActiveUser();
        userService.withdraw(user.getId());

        assertThatThrownBy(() -> userService.withdraw(user.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_INACTIVE);
    }

    @Test
    void missingUserIsRejected() {
        when(appUserRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.withdraw("missing"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    private AppUser givenActiveUser() {
        AppUser user = AppUser.createEmailUser("member@example.com", "포트너", CURRENT_HASH);
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }
}
