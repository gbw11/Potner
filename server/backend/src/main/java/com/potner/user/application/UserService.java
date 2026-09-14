package com.potner.user.application;

import com.potner.auth.domain.RefreshTokenRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.security.AuthenticatedUser;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import com.potner.user.dto.ChangePasswordRequest;
import com.potner.user.dto.UpdateNicknameRequest;
import com.potner.user.dto.UserMeResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public UserMeResponse getMe(AuthenticatedUser principal) {
        return toResponse(findActiveUser(principal.userId()));
    }

    @Transactional
    public UserMeResponse changeNickname(String userId, UpdateNicknameRequest request) {
        AppUser user = findActiveUser(userId);
        user.changeNickname(request.nickname());
        return toResponse(user);
    }

    /**
     * 비밀번호를 바꾸고 살아 있는 Refresh Token을 모두 폐기한다.
     *
     * <p>폐기하지 않으면 유출된 Refresh Token이 계속 유효해 비밀번호 변경의 목적이 반감된다.
     * 변경한 기기도 재로그인이 필요하다.
     */
    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        AppUser user = findActiveUser(userId);
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_UNCHANGED);
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllByUserId(userId, nowUtc());
    }

    /**
     * 계정을 탈퇴 상태로 바꾸고 Refresh Token을 모두 폐기한다.
     *
     * <p>물리 삭제가 아니다. {@code plant.user_id}와 {@code robot.user_id}가 ON DELETE RESTRICT
     * 이므로 행을 지우려면 외래키 정책을 여러 개 바꿔야 하고 그 과정에서 연쇄 삭제 위험이 생긴다.
     * 대신 인증 필터가 매 요청 계정 상태를 확인하므로 탈퇴 직후부터 모든 요청이 거부된다.
     * 물리 파기는 별도 작업으로 다룬다.
     */
    @Transactional
    public void withdraw(String userId) {
        AppUser user = findActiveUser(userId);
        LocalDateTime now = nowUtc();
        user.withdraw(now);
        refreshTokenRepository.revokeAllByUserId(userId, now);
    }

    private AppUser findActiveUser(String userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
        }
        return user;
    }

    private UserMeResponse toResponse(AppUser user) {
        return new UserMeResponse(user.getId(), user.getEmail(), user.getNickname());
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
