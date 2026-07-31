package com.potner.auth.application;

import com.potner.auth.domain.RefreshToken;
import com.potner.auth.domain.RefreshTokenRepository;
import com.potner.auth.dto.EmailAvailabilityRequest;
import com.potner.auth.dto.EmailAvailabilityResponse;
import com.potner.auth.dto.LoginRequest;
import com.potner.auth.dto.SignupRequest;
import com.potner.auth.dto.TokenResponse;
import com.potner.auth.jwt.IssuedToken;
import com.potner.auth.jwt.JwtProperties;
import com.potner.auth.jwt.JwtTokenProvider;
import com.potner.auth.jwt.RefreshTokenHasher;
import com.potner.auth.jwt.TokenType;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.security.AuthenticatedUser;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private RefreshTokenHasher refreshTokenHasher;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.systemUTC();
        passwordEncoder = new BCryptPasswordEncoder(4);
        jwtTokenProvider = new JwtTokenProvider(
                new JwtProperties(SECRET, 1_800_000, 1_209_600_000),
                clock
        );
        refreshTokenHasher = new RefreshTokenHasher();
        authService = new AuthService(
                appUserRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtTokenProvider,
                refreshTokenHasher,
                clock
        );
    }

    @Test
    void signupNormalizesEmailAndHashesPassword() {
        when(appUserRepository.existsByEmailIgnoreCase("member@example.com")).thenReturn(false);
        when(appUserRepository.saveAndFlush(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        authService.signup(new SignupRequest(" MEMBER@EXAMPLE.COM ", "password1", "potner"));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).saveAndFlush(captor.capture());
        AppUser savedUser = captor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("member@example.com");
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("password1");
        assertThat(passwordEncoder.matches("password1", savedUser.getPasswordHash())).isTrue();
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(appUserRepository.existsByEmailIgnoreCase("member@example.com")).thenReturn(true);

        assertBusinessError(
                () -> authService.signup(new SignupRequest("member@example.com", "password1", "potner")),
                ErrorCode.EMAIL_ALREADY_EXISTS
        );
    }

    @Test
    void emailAvailabilityNormalizesBeforeLookup() {
        when(appUserRepository.existsByEmailIgnoreCase("member@example.com")).thenReturn(false);

        EmailAvailabilityResponse response = authService.checkEmailAvailability(
                new EmailAvailabilityRequest(" MEMBER@EXAMPLE.COM "));

        // 가입과 같은 정규화를 써야 여기서 통과한 주소가 가입에서 409 로 막히지 않는다.
        assertThat(response.email()).isEqualTo("member@example.com");
        assertThat(response.available()).isTrue();
    }

    @Test
    void emailAvailabilityReportsTakenEmail() {
        when(appUserRepository.existsByEmailIgnoreCase("member@example.com")).thenReturn(true);

        EmailAvailabilityResponse response = authService.checkEmailAvailability(
                new EmailAvailabilityRequest("member@example.com"));

        assertThat(response.available()).isFalse();
    }

    @Test
    void loginIssuesBothTokenTypesAndStoresOnlyRefreshHash() {
        AppUser user = activeUser();
        when(appUserRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(user));

        TokenResponse response = authService.login(new LoginRequest("member@example.com", "password1"));

        assertThat(jwtTokenProvider.parseAccessToken(response.accessToken()).tokenType()).isEqualTo(TokenType.ACCESS);
        assertThat(jwtTokenProvider.parseRefreshToken(response.refreshToken()).tokenType()).isEqualTo(TokenType.REFRESH);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash())
                .isEqualTo(refreshTokenHasher.hash(response.refreshToken()))
                .isNotEqualTo(response.refreshToken());
    }

    @Test
    void loginDoesNotRevealWhetherEmailOrPasswordWasWrong() {
        when(appUserRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());
        assertBusinessError(
                () -> authService.login(new LoginRequest("missing@example.com", "password1")),
                ErrorCode.LOGIN_FAILED
        );

        when(appUserRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(activeUser()));
        assertBusinessError(
                () -> authService.login(new LoginRequest("member@example.com", "wrong-password1")),
                ErrorCode.LOGIN_FAILED
        );
    }

    @Test
    void loginRejectsInactiveAccount() {
        AppUser user = activeUser();
        user.suspend();
        when(appUserRepository.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(user));

        assertBusinessError(
                () -> authService.login(new LoginRequest("member@example.com", "password1")),
                ErrorCode.ACCOUNT_INACTIVE
        );
    }

    @Test
    void reissueReturnsNewAccessTokenForStoredRefreshToken() {
        AppUser user = activeUser();
        IssuedToken issuedRefreshToken = jwtTokenProvider.issueRefreshToken(user.getId());
        RefreshToken storedToken = RefreshToken.issue(
                user.getId(),
                refreshTokenHasher.hash(issuedRefreshToken.value()),
                LocalDateTime.ofInstant(issuedRefreshToken.expiresAt(), ZoneOffset.UTC)
        );
        when(refreshTokenRepository.findByTokenHash(storedToken.getTokenHash())).thenReturn(Optional.of(storedToken));
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

        TokenResponse response = authService.reissue(issuedRefreshToken.value());

        assertThat(response.refreshToken()).isEqualTo(issuedRefreshToken.value());
        assertThat(jwtTokenProvider.parseAccessToken(response.accessToken()).userId()).isEqualTo(user.getId());
    }

    @Test
    void logoutRevokesMatchingRefreshToken() {
        AppUser user = activeUser();
        IssuedToken issuedRefreshToken = jwtTokenProvider.issueRefreshToken(user.getId());
        RefreshToken storedToken = RefreshToken.issue(
                user.getId(),
                refreshTokenHasher.hash(issuedRefreshToken.value()),
                LocalDateTime.ofInstant(issuedRefreshToken.expiresAt(), ZoneOffset.UTC)
        );
        when(refreshTokenRepository.findByTokenHash(storedToken.getTokenHash())).thenReturn(Optional.of(storedToken));

        authService.logout(new AuthenticatedUser(user.getId()), issuedRefreshToken.value());

        assertThat(storedToken.isRevoked()).isTrue();
    }

    private AppUser activeUser() {
        return AppUser.createEmailUser(
                "member@example.com",
                "potner",
                passwordEncoder.encode("password1")
        );
    }

    private void assertBusinessError(Runnable invocation, ErrorCode expectedErrorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expectedErrorCode));
    }
}
