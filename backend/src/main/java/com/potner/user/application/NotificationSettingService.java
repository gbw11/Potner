package com.potner.user.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.user.domain.AppUserRepository;
import com.potner.user.domain.UserNotificationSetting;
import com.potner.user.domain.UserNotificationSettingRepository;
import com.potner.user.dto.NotificationSettingResponse;
import com.potner.user.dto.UpdateNotificationSettingRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class NotificationSettingService {

    private final UserNotificationSettingRepository settingRepository;
    private final AppUserRepository appUserRepository;

    public NotificationSettingService(
            UserNotificationSettingRepository settingRepository,
            AppUserRepository appUserRepository
    ) {
        this.settingRepository = settingRepository;
        this.appUserRepository = appUserRepository;
    }

    /** 설정을 한 번도 바꾸지 않은 사용자는 행이 없으므로 기본값을 돌려준다. 조회로 행을 만들지 않는다. */
    public NotificationSettingResponse getMySettings(String userId) {
        requireUser(userId);
        return NotificationSettingResponse.from(findOrDefault(userId));
    }

    @Transactional
    public NotificationSettingResponse updateMySettings(
            String userId,
            UpdateNotificationSettingRequest request
    ) {
        requireUser(userId);
        UserNotificationSetting setting = findOrDefault(userId);
        setting.apply(
                request.allEnabled(),
                request.pushEnabled(),
                request.plantCareEnabled(),
                request.marketingEnabled()
        );
        return NotificationSettingResponse.from(settingRepository.save(setting));
    }

    private UserNotificationSetting findOrDefault(String userId) {
        return settingRepository.findById(userId)
                .orElseGet(() -> UserNotificationSetting.defaults(userId));
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
}
