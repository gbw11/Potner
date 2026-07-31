package com.potner.happiness.application;

import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertRepository;
import com.potner.bloom.domain.PlantBloomRepository;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.domain.ScoreReason;
import com.potner.happiness.dto.StatusReportResponse;
import com.potner.light.domain.PlantDailyLight;
import com.potner.light.domain.PlantDailyLightRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 하루를 돌아보는 상태 리포트를 만든다.
 *
 * <p>저장하지 않는다. 계산에 쓰는 것이 전부 다른 테이블에 이미 있어 날짜만 주면 언제든 다시
 * 만들 수 있고, 규칙이 바뀌어도 과거를 재계산할 필요가 없다. 마이그레이션이 없다.
 *
 * <p><strong>알림만 센다.</strong> 누적 광량과 일조 시간도 이상하면 {@code alert} 에 기록되므로
 * ({@code AlertMetricType.DAILY_LIGHT}, {@code PHOTOPERIOD}) 광량 판정을 따로 또 깎으면 같은
 * 사실로 두 번 감점한다. 광량 배치가 알림을 열 때 {@code occurredAt} 을 판정 시각이 아니라 그
 * 광량 날짜의 시작으로 넣기 때문에, 날짜별로 세도 하루가 밀리지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class StatusReportService {

    private static final int FULL_SCORE = 100;
    private static final long SECONDS_PER_DAY = Duration.ofDays(1).toSeconds();

    private final PlantRepository plantRepository;
    private final AlertRepository alertRepository;
    private final PlantBloomRepository bloomRepository;
    private final PlantDailyLightRepository dailyLightRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final HappinessProperties properties;
    private final SensorQueryProperties sensorQueryProperties;

    public StatusReportService(
            PlantRepository plantRepository,
            AlertRepository alertRepository,
            PlantBloomRepository bloomRepository,
            PlantDailyLightRepository dailyLightRepository,
            SensorReadingRepository sensorReadingRepository,
            DeviceCommandRepository deviceCommandRepository,
            HappinessProperties properties,
            SensorQueryProperties sensorQueryProperties
    ) {
        this.plantRepository = plantRepository;
        this.alertRepository = alertRepository;
        this.bloomRepository = bloomRepository;
        this.dailyLightRepository = dailyLightRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.deviceCommandRepository = deviceCommandRepository;
        this.properties = properties;
        this.sensorQueryProperties = sensorQueryProperties;
    }

    public StatusReportResponse getReport(String userId, String plantId, LocalDate date) {
        requireOwnedPlant(userId, plantId);
        if (date == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        LocalDateTime fromUtc = startOfDayUtc(date);
        LocalDateTime toUtc = fromUtc.plusSeconds(SECONDS_PER_DAY - 1);
        Optional<PlantDailyLight> dailyLight =
                dailyLightRepository.findByPlantIdAndLightDate(plantId, date);

        // 측정이 없던 날은 점수를 주지 않는다. 알림도 없으니 100 점이 되는데, 그러면 기기가
        // 꺼져 있던 하루가 완벽한 하루로 보인다.
        if (sensorReadingRepository.countByPlantIdAndMeasuredAtBetween(plantId, fromUtc, toUtc) == 0) {
            return new StatusReportResponse(
                    plantId,
                    date,
                    null,
                    List.of(),
                    dailyLight.map(PlantDailyLight::getLightHours).orElse(null),
                    // 급수는 측정과 별개 경로라 센서가 조용했던 날에도 있을 수 있다.
                    deviceCommandRepository.sumDispensedMlBetween(plantId, fromUtc, toUtc)
            );
        }

        List<StatusReportResponse.ScoreAdjustment> adjustments = new ArrayList<>();
        alertRepository
                .findAllByPlantIdAndOccurredAtBetweenOrderByOccurredAtAsc(plantId, fromUtc, toUtc)
                .stream()
                .map(Alert::getMetricType)
                // 같은 지표로 여러 건이 열렸다 닫혔을 수 있다. 하루에 한 지표는 한 번만 깎는다.
                .distinct()
                // 감점 이유가 없는 지표(물 부족)는 여기서 빠진다. 근거는 ScoreReason.ofAlert 에.
                .map(ScoreReason::ofAlert)
                .flatMap(Optional::stream)
                .forEach(reason -> adjustments.add(
                        new StatusReportResponse.ScoreAdjustment(reason, -properties.alertPenalty())));

        if (bloomRepository.existsByPlantIdAndBloomDate(plantId, date)) {
            adjustments.add(new StatusReportResponse.ScoreAdjustment(
                    ScoreReason.BLOOMED, properties.bloomBonus()));
        }

        int score = adjustments.stream()
                .mapToInt(StatusReportResponse.ScoreAdjustment::points)
                .sum() + FULL_SCORE;

        return new StatusReportResponse(
                plantId,
                date,
                Math.clamp(score, 0, FULL_SCORE),
                adjustments,
                dailyLight.map(PlantDailyLight::getLightHours).orElse(null),
                // 그날 실제 급수량의 합이다. 급수 기록이 없으면 null 로, 0 과 구분된다.
                deviceCommandRepository.sumDispensedMlBetween(plantId, fromUtc, toUtc)
        );
    }

    /** 날짜 경계는 서비스 타임존 기준이다. UTC 자정으로 끊으면 하루가 9시간 밀린다. */
    private LocalDateTime startOfDayUtc(LocalDate date) {
        return date.atStartOfDay().minusSeconds(sensorQueryProperties.zoneOffsetSeconds());
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
