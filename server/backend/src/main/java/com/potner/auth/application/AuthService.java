package com.potner.auth.application;

import com.potner.auth.domain.RefreshToken;
import com.potner.auth.domain.RefreshTokenRepository;
import com.potner.auth.dto.EmailAvailabilityRequest;
import com.potner.auth.dto.EmailAvailabilityResponse;
import com.potner.auth.dto.LoginRequest;
import com.potner.auth.dto.SignupRequest;
import com.potner.auth.dto.SignupResponse;
import com.potner.auth.dto.TokenResponse;
import com.potner.auth.jwt.IssuedToken;
import com.potner.auth.jwt.JwtPayload;
import com.potner.auth.jwt.JwtTokenProvider;
import com.potner.auth.jwt.RefreshTokenHasher;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.security.AuthenticatedUser;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Clock clock;

    public AuthService(
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenHasher refreshTokenHasher,
            Clock clock
    ) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenHasher = refreshTokenHasher;
        this.clock = clock;
    }

    /**
     * 가입 전 이메일 사용 가능 여부를 본다.
     *
     * <p>가입과 같은 정규화·조회를 쓴다. 다른 방법으로 판정하면 여기서 통과한 주소가 가입에서
     * 409 로 막히는 일이 생긴다.
     *
     * <p><strong>결과는 보장이 아니다.</strong> 확인과 가입 사이에 다른 사용자가 같은 주소로
     * 가입할 수 있다. 최종 판정은 {@link #signup} 의 UNIQUE 제약이며, 이 조회는 사용자가 폼을
     * 다 채우기 전에 알려 주기 위한 것이다.
     */
    public EmailAvailabilityResponse checkEmailAvailability(EmailAvailabilityRequest request) {
        String email = normalizeEmail(request.email());
        return new EmailAvailabilityResponse(email, !appUserRepository.existsByEmailIgnoreCase(email));
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        AppUser user = AppUser.createEmailUser(
                email,
                request.nickname(),
                passwordEncoder.encode(request.password())
        );
        try {
            AppUser savedUser = appUserRepository.saveAndFlush(user);
            return new SignupResponse(savedUser.getId(), savedUser.getEmail(), savedUser.getNickname());
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        ensureActive(user);
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        IssuedToken accessToken = jwtTokenProvider.issueAccessToken(user.getId());
        IssuedToken refreshToken = jwtTokenProvider.issueRefreshToken(user.getId());
        refreshTokenRepository.save(RefreshToken.issue(
                user.getId(),
                refreshTokenHasher.hash(refreshToken.value()),
                LocalDateTime.ofInstant(refreshToken.expiresAt(), ZoneOffset.UTC)
        ));

        return TokenResponse.of(
                accessToken.value(),
                refreshToken.value(),
                jwtTokenProvider.accessTokenExpiresInSeconds()
        );
    }

    @Transactional
    public TokenResponse reissue(String refreshTokenValue) {
        JwtPayload payload = jwtTokenProvider.parseRefreshToken(refreshTokenValue);
        RefreshToken storedToken = findStoredToken(refreshTokenValue);
        validateStoredToken(storedToken, payload.userId());

        AppUser user = appUserRepository.findById(payload.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        ensureActive(user);

        IssuedToken accessToken = jwtTokenProvider.issueAccessToken(user.getId());
        return TokenResponse.of(
                accessToken.value(),
                refreshTokenValue,
                jwtTokenProvider.accessTokenExpiresInSeconds()
        );
    }

    @Transactional
    public void logout(AuthenticatedUser principal, String refreshTokenValue) {
        JwtPayload payload = jwtTokenProvider.parseRefreshToken(refreshTokenValue);
        RefreshToken storedToken = findStoredToken(refreshTokenValue);
        if (!payload.userId().equals(principal.userId()) || !storedToken.getUserId().equals(principal.userId())) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_MISMATCH);
        }
        validateStoredToken(storedToken, payload.userId());
        storedToken.revoke(now());
    }

    private RefreshToken findStoredToken(String refreshTokenValue) {
        return refreshTokenRepository.findByTokenHash(refreshTokenHasher.hash(refreshTokenValue))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    private void validateStoredToken(RefreshToken storedToken, String tokenUserId) {
        if (!storedToken.getUserId().equals(tokenUserId)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_MISMATCH);
        }
        if (storedToken.isRevoked()) {
            throw new BusinessException(ErrorCode.REVOKED_REFRESH_TOKEN);
        }
        if (storedToken.isExpired(now())) {
            throw new BusinessException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        }
    }

    private void ensureActive(AppUser user) {
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
