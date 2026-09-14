package com.potner.alert.application;

import com.potner.alert.config.AlertProperties;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 배수트레이가 찰 시점을 누적 급수량으로 추정해 알린다.
 *
 * <p>수위 센서를 두지 않는다. 급수할 때마다 실제 배출량이 {@code device_command.dispensed_ml}
 * 에 남으므로 하드웨어를 늘리지 않고 판정할 수 있다.
 *
 * <p><strong>임계값은 고정 ml 이 아니라 식물별 권장 급수량의 배수다.</strong> 트레이는 총
 * 배수량으로 차므로 회당 급수량 차이는 무관하지만, 트레이 용량은 화분 크기에 따라 다르다.
 * {@code recommendedWateringMl} 이 화분 크기의 대리 지표라서 그 값에 배수를 곱한다. 사용자가
 * 케어 설정에서 급수량을 조절하면 임계값도 따라 움직인다.
 *
 * <p>"마지막으로 비운 시각" 을 별도 테이블에 두지 않는다. 가장 최근에 해제된 배수트레이 알림의
 * {@code resolvedAt} 이 그 값이다. 해제 이력이 없으면 등록 이후 전체를 합산한다.
 *
 * <p>알림은 활성 1건 제약({@code active_key})이 중복을 막는다. 비울 때까지 급수를 더 해도
 * 새 알림이 생기지 않고, 비우면 다음 주기에 다시 열린다.
 *
 * <p>배수 비율은 흙·화분마다 다르다(다육은 빨리 빠지고 관엽은 흙이 머금는다). 종별 계수가
 * 있어야 정확하지만 종 기준 시드에 배수 데이터가 없어 배수 하나로 시작한다. 실사용에서 너무
 * 자주/늦게 뜨면 그때 종별로 쪼갠다.
 */
@Service
public class DrainageTrayService {

    /**
     * 해제 이력이 없을 때 합산 시작점이다. 전체 기간을 뜻하는 값으로, 서비스 개시보다 충분히
     * 과거면 된다.
     */
    private static final LocalDateTime BEGINNING_OF_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final AlertRepository alertRepository;
    private final DeviceCommandRepository commandRepository;
    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final AlertProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public DrainageTrayService(
            AlertRepository alertRepository,
            DeviceCommandRepository commandRepository,
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            AlertProperties properties,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.alertRepository = alertRepository;
        this.commandRepository = commandRepository;
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 급수가 끝난 뒤 트레이 상태를 다시 본다.
     *
     * @return 알림을 새로 열었으면 {@code true}
     */
    @Transactional
    public boolean evaluate(String plantId) {
        Plant plant = plantRepository.findByIdAndStatusNot(plantId, PlantStatus.DELETED).orElse(null);
        if (plant == null) {
            return false;
        }
        // 이미 열려 있으면 누적을 셀 필요가 없다. 비울 때까지 알림은 하나다.
        if (activeAlert(plantId).isPresent()) {
            return false;
        }

        BigDecimal threshold = threshold(plantId);
        if (threshold == null) {
            // 급수량이 설정되지 않은 식물이다. 임계값을 만들 근거가 없으면 판정하지 않는다 —
            // 자동 급수가 같은 이유로 발행하지 않는 것과 같은 방침이다.
            return false;
        }

        BigDecimal accumulated = accumulatedSinceLastEmptied(plantId);
        if (accumulated.compareTo(threshold) < 0) {
            return false;
        }

        Alert alert = Alert.openDrainageTray(
                plant.getUser().getId(),
                plantId,
                accumulated,
                threshold,
                nowUtc()
        );
        alertRepository.save(alert);
        eventPublisher.publishEvent(new AlertOpenedEvent(
                alert.getId(),
                alert.getUserId(),
                alert.getPlantId(),
                plant.getNickname(),
                AlertMetricType.DRAINAGE_TRAY,
                AlertDeviation.HIGH
        ));
        return true;
    }

    /**
     * 사용자가 트레이를 비웠다고 알린다. 활성 알림을 해제하고, 그 시각이 다음 누적의 기준점이 된다.
     *
     * <p>비웠는지 서버가 검증할 방법은 없다. 센서 방식과의 본질적 차이이며, 넘칠 위험이 실제로
     * 문제가 되면 수위 센서를 붙여 보강하는 경로는 열려 있다.
     */
    @Transactional
    public void markEmptied(String userId, String plantId) {
        requireOwnedPlant(userId, plantId);
        Alert active = activeAlert(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND));
        LocalDateTime now = nowUtc();
        active.resolve(now);
        // 읽음도 함께 남긴다. 비웠다는 것은 알림을 확인했다는 뜻이라, 읽지 않은 알림 수에 계속
        // 남아 있으면 사용자가 같은 알림을 다시 찾게 된다.
        active.markRead(now);
    }

    /** 마지막으로 비운 뒤 실제로 나간 물의 합이다. 급수 기록이 없으면 0 이다. */
    private BigDecimal accumulatedSinceLastEmptied(String plantId) {
        LocalDateTime since = alertRepository
                .findFirstByPlantIdAndMetricTypeAndResolvedAtIsNotNullOrderByResolvedAtDesc(
                        plantId, AlertMetricType.DRAINAGE_TRAY)
                .map(Alert::getResolvedAt)
                .orElse(BEGINNING_OF_TIME);
        return Optional
                .ofNullable(commandRepository.sumDispensedMlAfter(plantId, since))
                .orElse(BigDecimal.ZERO);
    }

    /** 권장 급수량 × 배수. 급수량이 없으면 {@code null} 이다. */
    private BigDecimal threshold(String plantId) {
        return profileRepository.findByPlantId(plantId)
                .map(PlantGrowthProfile::getRecommendedWateringMl)
                .map(amount -> amount.multiply(
                        BigDecimal.valueOf(properties.drainageTrayWateringMultiplier())))
                .orElse(null);
    }

    private Optional<Alert> activeAlert(String plantId) {
        return alertRepository.findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                plantId, AlertMetricType.DRAINAGE_TRAY);
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
