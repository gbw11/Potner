package com.potner.light.application;

import com.potner.alert.application.AlertEvaluationResult;
import com.potner.alert.application.AlertEvaluationService;
import com.potner.alert.domain.AlertMetricType;
import com.potner.light.config.DailyLightProperties;
import com.potner.light.domain.DailyLightAccumulation;
import com.potner.light.domain.DailyLightStatus;
import com.potner.light.domain.PlantDailyLight;
import com.potner.light.domain.PlantDailyLightRepository;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class DailyLightAggregationService {

    private static final long SECONDS_PER_DAY = Duration.ofDays(1).toSeconds();

    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final PlantDailyLightRepository dailyLightRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final AlertEvaluationService alertEvaluationService;
    private final DailyLightProperties properties;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public DailyLightAggregationService(
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            PlantDailyLightRepository dailyLightRepository,
            SensorReadingRepository sensorReadingRepository,
            AlertEvaluationService alertEvaluationService,
            DailyLightProperties properties,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.dailyLightRepository = dailyLightRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.alertEvaluationService = alertEvaluationService;
        this.properties = properties;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    /**
     * 하루가 마감된 날짜를 집계해 저장하고 판정 결과를 알림으로 넘긴다.
     * 같은 날짜를 다시 호출하면 기존 행을 갱신한다.
     */
    @Transactional
    public DailyLightAggregationResult aggregateAndStore(String plantId, LocalDate lightDate) {
        Plant plant = plantRepository.findById(plantId).orElse(null);
        if (plant == null || plant.getStatus() != PlantStatus.ACTIVE) {
            return DailyLightAggregationResult.skipped(lightDate, DailyLightStatus.NOT_APPLICABLE);
        }
        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId).orElse(null);
        if (profile == null) {
            return DailyLightAggregationResult.skipped(lightDate, DailyLightStatus.NOT_APPLICABLE);
        }
        GrowthProfileValues values = GrowthProfileValues.from(profile);

        LocalDateTime fromUtc = startOfDayUtc(lightDate);
        DailyLightAccumulationSnapshot snapshot = accumulate(
                plantId,
                fromUtc,
                fromUtc.plusSeconds(SECONDS_PER_DAY)
        );
        BigDecimal coveragePct = snapshot.coveragePct(SECONDS_PER_DAY);

        BigDecimal photoperiodTarget = values.photoperiodHours();
        BigDecimal photoperiodTolerance = photoperiodTarget
                .multiply(properties.photoperiodToleranceRatio());
        BigDecimal photoperiodMin = photoperiodTarget.subtract(photoperiodTolerance);
        BigDecimal photoperiodMax = photoperiodTarget.add(photoperiodTolerance);

        boolean enoughData = coveragePct.compareTo(properties.minCoveragePct()) >= 0;
        DailyLightStatus lightStatus = enoughData
                ? judge(
                        snapshot.accumulatedLuxHour(),
                        values.dailyLightMinLuxHour(),
                        values.dailyLightMaxLuxHour())
                : DailyLightStatus.INSUFFICIENT_DATA;
        DailyLightStatus photoperiodStatus = enoughData
                ? judge(snapshot.lightHours(), photoperiodMin, photoperiodMax)
                : DailyLightStatus.INSUFFICIENT_DATA;

        store(
                plantId,
                lightDate,
                snapshot,
                coveragePct,
                values,
                photoperiodTarget,
                lightStatus,
                photoperiodStatus
        );

        AlertEvaluationResult lightAlert = feedAlert(
                plantId,
                AlertMetricType.DAILY_LIGHT,
                lightStatus,
                snapshot.accumulatedLuxHour(),
                values.dailyLightMinLuxHour(),
                values.dailyLightMaxLuxHour(),
                fromUtc
        );
        AlertEvaluationResult photoperiodAlert = feedAlert(
                plantId,
                AlertMetricType.PHOTOPERIOD,
                photoperiodStatus,
                snapshot.lightHours(),
                photoperiodMin,
                photoperiodMax,
                fromUtc
        );
        return new DailyLightAggregationResult(
                lightDate,
                lightStatus,
                photoperiodStatus,
                lightAlert,
                photoperiodAlert
        );
    }

    /** 활성 식물 전체의 전일분을 집계한다. 한 식물이 실패해도 나머지는 계속 처리한다. */
    public int aggregatePreviousDayForActivePlants() {
        LocalDate previousDay = today().minusDays(1);
        int processed = 0;
        for (Plant plant : plantRepository.findAllByStatus(PlantStatus.ACTIVE)) {
            aggregateAndStore(plant.getId(), previousDay);
            processed++;
        }
        return processed;
    }

    /** 진행 중인 오늘의 누적값이다. 저장하거나 판정하지 않는다. */
    @Transactional(readOnly = true)
    public DailyLightAccumulationSnapshot accumulateToday(String plantId) {
        LocalDateTime fromUtc = startOfDayUtc(today());
        return accumulate(plantId, fromUtc, nowUtc());
    }

    /** 서비스 타임존 기준 오늘 날짜다. */
    public LocalDate today() {
        return nowUtc().plusSeconds(zoneOffsetSeconds()).toLocalDate();
    }

    /** 서비스 타임존 기준 하루가 시작되는 시각을 UTC로 돌려준다. */
    public LocalDateTime startOfDayUtc(LocalDate lightDate) {
        return lightDate.atStartOfDay().minusSeconds(zoneOffsetSeconds());
    }

    public long elapsedSecondsToday() {
        return Duration.between(startOfDayUtc(today()), nowUtc()).toSeconds();
    }

    private DailyLightAccumulationSnapshot accumulate(
            String plantId,
            LocalDateTime fromUtc,
            LocalDateTime toUtc
    ) {
        if (!fromUtc.isBefore(toUtc)) {
            return new DailyLightAccumulationSnapshot(
                    scale2(BigDecimal.ZERO),
                    scale2(BigDecimal.ZERO),
                    0L,
                    0L
            );
        }
        DailyLightAccumulation accumulation = sensorReadingRepository.aggregateDailyLight(
                plantId,
                fromUtc,
                toUtc,
                properties.lightOnThresholdLux(),
                properties.maxGapSeconds()
        );
        return new DailyLightAccumulationSnapshot(
                scale2(accumulation.getAccumulatedLuxHour()),
                scale2(accumulation.getLightHours()),
                accumulation.getCoveredSeconds(),
                accumulation.getSampleCount()
        );
    }

    private void store(
            String plantId,
            LocalDate lightDate,
            DailyLightAccumulationSnapshot snapshot,
            BigDecimal coveragePct,
            GrowthProfileValues values,
            BigDecimal photoperiodTarget,
            DailyLightStatus lightStatus,
            DailyLightStatus photoperiodStatus
    ) {
        PlantDailyLight daily = dailyLightRepository
                .findByPlantIdAndLightDate(plantId, lightDate)
                .orElseGet(() -> PlantDailyLight.create(plantId, lightDate));
        daily.apply(
                snapshot.accumulatedLuxHour(),
                snapshot.lightHours(),
                coveragePct,
                snapshot.sampleCount(),
                values.dailyLightTargetLuxHour(),
                photoperiodTarget,
                values.dailyLightMinLuxHour(),
                values.dailyLightMaxLuxHour(),
                lightStatus,
                photoperiodStatus,
                nowUtc()
        );
        dailyLightRepository.save(daily);
    }

    /**
     * 판정이 성립한 경우에만 알림 판단으로 넘긴다.
     * 데이터가 부족하거나 기준이 없을 때는 알림을 만들지도, 기존 알림을 해제하지도 않는다.
     */
    private AlertEvaluationResult feedAlert(
            String plantId,
            AlertMetricType metricType,
            DailyLightStatus status,
            BigDecimal value,
            BigDecimal min,
            BigDecimal max,
            LocalDateTime occurredAt
    ) {
        if (status == DailyLightStatus.INSUFFICIENT_DATA
                || status == DailyLightStatus.NOT_APPLICABLE) {
            return AlertEvaluationResult.UNCHANGED;
        }
        return alertEvaluationService.evaluateDailyMetric(
                plantId,
                metricType,
                value,
                min,
                max,
                occurredAt
        );
    }

    private DailyLightStatus judge(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (min == null || max == null) {
            return DailyLightStatus.NOT_APPLICABLE;
        }
        if (value.compareTo(min) < 0) {
            return DailyLightStatus.LOW;
        }
        if (value.compareTo(max) > 0) {
            return DailyLightStatus.HIGH;
        }
        return DailyLightStatus.NORMAL;
    }

    private long zoneOffsetSeconds() {
        return sensorQueryProperties.zoneOffsetSeconds();
    }

    private BigDecimal scale2(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
