package com.potner.push.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.push.domain.FcmTokenRepository;
import com.potner.push.dto.RegisterFcmTokenRequest;
import com.potner.user.domain.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;

@Service
@Transactional(readOnly = true)
public class FcmTokenService {

    /** {@code fcm_token.installation_id} 컬럼 길이다. 넘치면 저장 단계에서 잘려 500이 나간다. */
    private static final int MAX_INSTALLATION_ID_LENGTH = 128;

    private final FcmTokenRepository tokenRepository;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    public FcmTokenService(
            FcmTokenRepository tokenRepository,
            AppUserRepository appUserRepository,
            Clock clock
    ) {
        this.tokenRepository = tokenRepository;
        this.appUserRepository = appUserRepository;
        this.clock = clock;
    }

    /**
     * 기기를 등록하거나 갱신한다.
     *
     * <p>앱은 로그인과 세션 복원마다, 그리고 토큰이 회전할 때마다 이 API를 부른다. 그래서 같은
     * 설치 ID로 반복 호출되는 것이 정상이고 upsert로 처리한다.
     */
    @Transactional
    public void register(String userId, String installationId, RegisterFcmTokenRequest request) {
        requireUser(userId);
        tokenRepository.upsert(
                requireValidInstallationId(installationId),
                userId,
                request.token().trim(),
                request.platform().name(),
                nowUtc()
        );
    }

    /**
     * 기기 등록을 해제한다.
     *
     * <p>지운 행이 없어도 성공으로 둔다. 앱이 로그아웃 중 실패해 재시도할 수 있어야 하고,
     * 남의 설치 ID를 넣었을 때 존재 여부를 알려주지 않기 위함이기도 하다.
     */
    @Transactional
    public void unregister(String userId, String installationId) {
        requireUser(userId);
        tokenRepository.deleteOwned(userId, requireValidInstallationId(installationId));
    }

    /**
     * 발송에서 영구 무효로 확인된 기기를 비활성으로 내린다.
     *
     * <p>발송기가 트랜잭션 밖에서 돌기 때문에 쓰기를 여기로 모은다. 소유자를 확인하지 않는
     * 이유는 대상이 사용자 입력이 아니라 FCM 응답에서 나온 설치 ID 이기 때문이다.
     */
    @Transactional
    public int deactivate(Collection<String> installationIds) {
        if (installationIds.isEmpty()) {
            return 0;
        }
        return tokenRepository.deactivateAll(installationIds);
    }

    /**
     * 경로 변수는 {@code @Valid}가 적용되지 않으므로 직접 확인한다.
     *
     * <p>{@code ConstraintViolationException}은 전역 예외 처리에 없어 500이 되기 때문에
     * {@code @Validated}로 검증하지 않는다.
     */
    private String requireValidInstallationId(String installationId) {
        if (installationId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String trimmed = installationId.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_INSTALLATION_ID_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return trimmed;
    }

    /**
     * 탈퇴 등으로 사라진 사용자의 Access Token이 아직 유효할 수 있다.
     * 확인하지 않으면 저장 단계에서 외래키 위반으로 500이 나간다.
     */
    private void requireUser(String userId) {
        if (!appUserRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
