package com.potner.alert.application;

import com.potner.alert.config.AlertProperties;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.application.SensorThresholds;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
public class AlertEvaluationService {

    private final AlertRepository alertRepository;
    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final AlertProperties alertProperties;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public AlertEvaluationService(
            AlertRepository alertRepository,
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            SensorReadingRepository sensorReadingRepository,
            AlertProperties alertProperties,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.alertRepository = alertRepository;
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.alertProperties = alertProperties;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    /**
     * 새 측정값이 저장된 뒤 해당 식물·센서의 이상 상태를 다시 판정한다.
     *
     * <p>측정값 저장과 별도 트랜잭션으로 동작하므로 판정이 실패해도 이미 저장된 측정값은 남는다.
     */
    @Transactional
    public AlertEvaluationResult evaluate(String plantId, SensorType sensorType) {
        Optional<AlertMetricType> metricType = AlertMetricType.ofInstantSensor(sensorType);
        if (metricType.isEmpty()) {
            return AlertEvaluationResult.NOT_APPLICABLE;
        }

        Plant plant = plantRepository.findById(plantId).orElse(null);
        if (plant == null || plant.getStatus() == PlantStatus.DELETED) {
            return AlertEvaluationResult.PLANT_NOT_ACTIVE;
        }

        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId).orElse(null);
        if (profile == null) {
            return AlertEvaluationResult.GROWTH_PROFILE_NOT_FOUND;
        }

        GrowthProfileValues values = GrowthProfileValues.from(profile);
        SensorThresholds thresholds = SensorThresholds.of(sensorType, values);
        if (!thresholds.judgeable()) {
            return AlertEvaluationResult.NOT_APPLICABLE;
        }

        List<SensorReading> samples = findRecentSamples(plantId, sensorType);
        if (samples.size() < alertProperties.sampleSize()) {
            return AlertEvaluationResult.INSUFFICIENT_DATA;
        }

        BigDecimal representative = DeviationEvaluator.median(
                samples.stream().map(SensorReading::getMeasuredValue).toList()
        );
        LocalDateTime occurredAt = samples.getFirst().getMeasuredAt();
        Optional<AlertDeviation> detected = DeviationEvaluator.detect(representative, thresholds);
        Alert active = alertRepository
                .findByPlantIdAndMetricTypeAndResolvedAtIsNull(plantId, metricType.get())
                .orElse(null);

        if (active == null) {
            if (detected.isEmpty()) {
                return AlertEvaluationResult.UNCHANGED;
            }
            openAlert(plant, metricType.get(), detected.get(), representative, thresholds, occurredAt);
            return AlertEvaluationResult.CREATED;
        }

        if (detected.isPresent() && detected.get() != active.getDeviation()) {
            active.resolve(nowUtc());
            openAlert(plant, metricType.get(), detected.get(), representative, thresholds, occurredAt);
            return AlertEvaluationResult.SWITCHED;
        }
        if (detected.isEmpty() && DeviationEvaluator.recovered(
                representative,
                thresholds,
                active.getDeviation(),
                alertProperties.hysteresisRatio())) {
            active.resolve(nowUtc());
            return AlertEvaluationResult.RESOLVED;
        }
        return AlertEvaluationResult.UNCHANGED;
    }

    /**
     * 최신순으로 표본을 가져온다. 장치가 오래 조용했던 구간의 값으로 판정하지 않도록
     * 센서 조회와 같은 신선도 창을 적용한다.
     */
    private List<SensorReading> findRecentSamples(String plantId, SensorType sensorType) {
        LocalDateTime freshSince = nowUtc()
                .minusMinutes(sensorQueryProperties.freshnessThresholdMinutes());
        return sensorReadingRepository
                .findByPlantIdAndSensorTypeAndQualityAndMeasuredAtGreaterThanEqualOrderByMeasuredAtDesc(
                        plantId,
                        sensorType,
                        SensorQuality.GOOD,
                        freshSince,
                        PageRequest.of(0, alertProperties.sampleSize())
                );
    }

    private void openAlert(
            Plant plant,
            AlertMetricType metricType,
            AlertDeviation deviation,
            BigDecimal representative,
            SensorThresholds thresholds,
            LocalDateTime occurredAt
    ) {
        alertRepository.save(Alert.open(
                plant.getUser().getId(),
                plant.getId(),
                metricType,
                deviation,
                representative,
                thresholds.min(),
                thresholds.max(),
                occurredAt
        ));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
