package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.push.application.FcmTokenService;
import com.potner.push.application.PushSendResult;
import com.potner.push.application.PushSender;
import com.potner.push.application.PushTargetResolver;
import com.potner.push.application.RepottingPushMessageFactory;
import com.potner.push.domain.FcmToken;
import com.potner.user.domain.NotificationCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 분갈이 시기 알림을 손으로 한 번 보낸다.
 *
 * <p>시기 판정을 서버가 하지 않는다. 판정하려면 "마지막으로 분갈이한 날" 이 있어야 하는데
 * 그건 사용자가 앱에서 기록해야 하는 값이고, 그 기록을 담을 테이블이 아직 없다. 지금은 앱이
 * 식물 등록일로 D-day 를 계산해 화면에 안내하고, 알림은 이 통로로 보낸다.
 *
 * <p>이력을 남기지 않는다. 같은 식물에 두 번 부르면 두 번 나간다 — 중복을 막을 기준
 * ("이미 이번 주기에 알렸다") 이 마지막 분갈이 날짜 없이는 정의되지 않는다. 시연·진단용이라
 * 그 편이 오히려 낫다.
 *
 * <p>{@code alert} 에도 기록하지 않는다. 그 테이블은 센서 판정으로 열리고 해소되는 것이라
 * "지금 이상하다" 를 담는데, 분갈이는 해소되는 상태가 아니라 한 번 알리면 끝나는 안내다.
 *
 * <p>클래스에 트랜잭션을 걸지 않는다. 조회는 리포지터리가 알아서 하고, 무효 토큰 정리
 * ({@link FcmTokenService#deactivate})는 자기 트랜잭션에서 쓰기를 한다. 여기에
 * {@code readOnly = true} 를 걸면 그 쓰기가 읽기 전용 트랜잭션에 합류해 버린다.
 */
@Service
public class RepottingReminderService {

    private static final Logger log = LoggerFactory.getLogger(RepottingReminderService.class);

    private final PlantRepository plantRepository;
    private final PushTargetResolver pushTargetResolver;
    private final RepottingPushMessageFactory messageFactory;
    private final PushSender pushSender;
    private final FcmTokenService fcmTokenService;

    public RepottingReminderService(
            PlantRepository plantRepository,
            PushTargetResolver pushTargetResolver,
            RepottingPushMessageFactory messageFactory,
            PushSender pushSender,
            FcmTokenService fcmTokenService
    ) {
        this.plantRepository = plantRepository;
        this.pushTargetResolver = pushTargetResolver;
        this.messageFactory = messageFactory;
        this.pushSender = pushSender;
        this.fcmTokenService = fcmTokenService;
    }

    /**
     * @throws BusinessException 그 사용자의 식물이 아니거나 삭제됐으면 {@code PLANT_NOT_FOUND},
     *                           수신 설정이 꺼져 있거나 등록된 기기가 없으면
     *                           {@code PUSH_TARGET_NOT_FOUND}
     */
    public void send(String userId, String plantId) {
        Plant plant = plantRepository
                .findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));

        // 분갈이도 케어 알림이다. NotificationCategory.PLANT_CARE 의 설명이 케어 알림을 포함한다.
        List<FcmToken> targets = pushTargetResolver.resolve(userId, NotificationCategory.PLANT_CARE);
        if (targets.isEmpty()) {
            // 이벤트 기반 알림은 대상이 없으면 조용히 넘어가지만, 여기서는 사람이 버튼을 눌러
            // 부른 것이라 아무 일도 안 일어난 이유를 알려 줘야 한다.
            throw new BusinessException(ErrorCode.PUSH_TARGET_NOT_FOUND);
        }

        PushSendResult result = pushSender.send(
                targets,
                messageFactory.create(plant.getId(), plant.getNickname())
        );
        int deactivated = fcmTokenService.deactivate(result.invalidInstallationIds());
        log.info(
                "Repotting reminder push handled: plantId={}, targets={}, success={}, deactivated={}",
                plantId,
                targets.size(),
                result.successCount(),
                deactivated
        );
    }
}
