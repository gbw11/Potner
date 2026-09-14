package com.potner.light.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.light.config.DailyLightProperties;
import com.potner.light.domain.PlantDailyLight;
import com.potner.light.domain.PlantDailyLightRepository;
import com.potner.light.dto.DailyLightDayResponse;
import com.potner.light.dto.DailyLightResponse;
import com.potner.light.dto.DailyLightTodayResponse;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DailyLightQueryService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final PlantDailyLightRepository dailyLightRepository;
    private final DailyLightAggregationService aggregationService;
    private final DailyLightProperties properties;
    private final SensorQueryProperties sensorQueryProperties;

    public DailyLightQueryService(
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            PlantDailyLightRepository dailyLightRepository,
            DailyLightAggregationService aggregationService,
            DailyLightProperties properties,
            SensorQueryProperties sensorQueryProperties
    ) {
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.dailyLightRepository = dailyLightRepository;
        this.aggregationService = aggregationService;
        this.properties = properties;
        this.sensorQueryProperties = sensorQueryProperties;
    }

    public DailyLightResponse getDailyLight(String userId, String plantId, int days) {
        if (days < 1 || days > properties.maxHistoryDays()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        requireOwnedPlant(userId, plantId);
        GrowthProfileValues values = findAppliedProfile(plantId);

        LocalDate today = aggregationService.today();
        DailyLightAccumulationSnapshot snapshot = aggregationService.accumulateToday(plantId);
        DailyLightTodayResponse todayResponse = new DailyLightTodayResponse(
                today,
                snapshot.accumulatedLuxHour(),
                values.dailyLightTargetLuxHour(),
                progressPct(snapshot.accumulatedLuxHour(), values.dailyLightTargetLuxHour()),
                snapshot.lightHours(),
                values.photoperiodHours(),
                snapshot.coveragePct(aggregationService.elapsedSecondsToday()),
                snapshot.sampleCount()
        );

        // 오늘은 아직 확정되지 않았으므로 이력에서 제외한다.
        List<DailyLightDayResponse> history = dailyLightRepository
                .findByPlantIdAndLightDateBetweenOrderByLightDateDesc(
                        plantId,
                        today.minusDays(days),
                        today.minusDays(1))
                .stream()
                .map(DailyLightDayResponse::from)
                .toList();

        return new DailyLightResponse(
                plantId,
                sensorQueryProperties.zoneOffset(),
                todayResponse,
                history
        );
    }

    private BigDecimal progressPct(BigDecimal accumulated, BigDecimal target) {
        if (target == null || target.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return accumulated.multiply(HUNDRED).divide(target, 2, RoundingMode.HALF_UP);
    }

    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    private GrowthProfileValues findAppliedProfile(String plantId) {
        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND));
        return GrowthProfileValues.from(profile);
    }
}
