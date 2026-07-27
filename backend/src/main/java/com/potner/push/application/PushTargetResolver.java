package com.potner.push.application;

import com.potner.push.domain.FcmToken;
import com.potner.push.domain.FcmTokenRepository;
import com.potner.user.domain.NotificationCategory;
import com.potner.user.domain.UserNotificationSetting;
import com.potner.user.domain.UserNotificationSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 푸시를 받을 기기를 고른다.
 *
 * <p>수신 설정 판정과 토큰 조회를 한곳에 모아 둔 이유는, 발송기가 설정을 읽는 것을 잊는 실패를
 * 구조적으로 막기 위함이다. 발송기는 이 결과만 받고 목록이 비어 있으면 아무것도 하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class PushTargetResolver {

    private final UserNotificationSettingRepository settingRepository;
    private final FcmTokenRepository tokenRepository;

    public PushTargetResolver(
            UserNotificationSettingRepository settingRepository,
            FcmTokenRepository tokenRepository
    ) {
        this.settingRepository = settingRepository;
        this.tokenRepository = tokenRepository;
    }

    /**
     * 설정을 통과하면 활성 토큰을, 그렇지 않으면 빈 목록을 돌려준다.
     *
     * <p>설정을 한 번도 바꾸지 않은 사용자는 행이 없으므로 기본값으로 취급한다. 조회로 행을
     * 만들지 않는다. 기본값은 마스터·푸시·케어가 켜져 있고 마케팅만 꺼져 있다.
     */
    public List<FcmToken> resolve(String userId, NotificationCategory category) {
        UserNotificationSetting setting = settingRepository.findById(userId)
                .orElseGet(() -> UserNotificationSetting.defaults(userId));
        if (!setting.allowsPush(category)) {
            return List.of();
        }
        return tokenRepository.findAllByUserIdAndActiveIsTrue(userId);
    }
}
