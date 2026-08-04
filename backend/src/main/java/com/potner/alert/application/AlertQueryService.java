package com.potner.alert.application;

import com.potner.alert.config.AlertProperties;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertRepository;
import com.potner.alert.dto.AlertListResponse;
import com.potner.alert.dto.AlertResponse;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AlertQueryService {

    private final AlertRepository alertRepository;
    private final PlantRepository plantRepository;
    private final AlertProperties properties;
    private final Clock clock;

    public AlertQueryService(
            AlertRepository alertRepository,
            PlantRepository plantRepository,
            AlertProperties properties,
            Clock clock
    ) {
        this.alertRepository = alertRepository;
        this.plantRepository = plantRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public AlertListResponse getMyAlerts(
            String userId,
            boolean unreadOnly,
            boolean activeOnly,
            int page,
            int size
    ) {
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        int pageSize = Math.min(size, properties.maxPageSize());

        Page<Alert> alerts = alertRepository.findOwned(
                userId,
                unreadOnly,
                activeOnly,
                PageRequest.of(page, pageSize)
        );
        Map<String, String> plantNames = findPlantNames(alerts.getContent());
        List<AlertResponse> items = alerts.getContent().stream()
                .map(alert -> AlertResponse.of(alert, plantNames.get(alert.getPlantId())))
                .toList();

        return new AlertListResponse(
                items,
                page,
                pageSize,
                alerts.getTotalElements(),
                alerts.getTotalPages(),
                alertRepository.countByUserIdAndReadAtIsNull(userId)
        );
    }

    @Transactional
    public void markRead(String userId, String alertId) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND));
        alert.markRead(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * 사용자가 목록에서 치운다. 행을 지우지 않고 목록 조회에서만 뺀다.
     *
     * <p>지우면 안 되는 이유가 셋이다 — 행복도 점수가 소급해 바뀌고, 자동 급수·말리기가 다시
     * 돌고, 그날 일기의 근거가 달라진다. V30 마이그레이션 주석에 적어 두었다.
     */
    @Transactional
    public void dismiss(String userId, String alertId) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND));
        alert.dismiss(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** 치운 것을 되돌린다. 앱의 스와이프 되돌리기가 부른다. 읽음은 되돌리지 않는다. */
    @Transactional
    public void restore(String userId, String alertId) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND));
        alert.restore();
    }

    /**
     * 알림 목록에 식물 이름을 채운다. Alert가 식물을 연관으로 들고 있지 않으므로
     * 한 페이지 분량을 한 번에 조회해 N+1을 만들지 않는다.
     */
    private Map<String, String> findPlantNames(List<Alert> alerts) {
        List<String> plantIds = alerts.stream()
                .map(Alert::getPlantId)
                .distinct()
                .toList();
        if (plantIds.isEmpty()) {
            return Map.of();
        }
        return plantRepository.findAllById(plantIds).stream()
                .collect(Collectors.toMap(Plant::getId, Plant::getNickname, (first, second) -> first));
    }
}
