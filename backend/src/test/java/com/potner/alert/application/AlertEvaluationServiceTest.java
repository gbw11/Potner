package com.potner.alert.application;

import com.potner.alert.config.AlertProperties;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.domain.SensorUnit;
import com.potner.user.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertEvaluationServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String NICKNAME = "로지";
    private static final Instant NOW = Instant.parse("2026-07-26T00:40:00Z");
    private static final LocalDateTime MEASURED_AT = LocalDateTime.of(2026, 7, 26, 0, 39);

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AlertEvaluationService alertEvaluationService;

    @BeforeEach
    void setUp() {
        alertEvaluationService = new AlertEvaluationService(
                alertRepository,
                plantRepository,
                profileRepository,
                sensorReadingRepository,
                new AlertProperties(3, new BigDecimal("0.1"), 100, 8),
                new SensorQueryProperties("+09:00", 15, 14, 365),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void illuminanceIsNotEvaluatedAsInstantValue() {
        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.ILLUMINANCE))
                .isEqualTo(AlertEvaluationResult.NOT_APPLICABLE);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void deletedPlantIsNotEvaluated() {
        Plant plant = mock(Plant.class);
        when(plant.getStatus()).thenReturn(PlantStatus.DELETED);
        when(plantRepository.findById(PLANT_ID)).thenReturn(Optional.of(plant));

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.PLANT_NOT_ACTIVE);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void missingGrowthProfileIsReported() {
        givenActivePlant();
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.empty());

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.GROWTH_PROFILE_NOT_FOUND);
    }

    @Test
    void tooFewSamplesKeepTheServiceSilent() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("34.00", "35.00");

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.INSUFFICIENT_DATA);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void noisySamplesBelowMinimumCreateLowAlert() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("35.00", "41.00", "34.00");
        givenNoActiveAlert();

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.CREATED);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(captor.capture());
        Alert created = captor.getValue();
        assertThat(created.getUserId()).isEqualTo(USER_ID);
        assertThat(created.getPlantId()).isEqualTo(PLANT_ID);
        assertThat(created.getMetricType()).isEqualTo(AlertMetricType.SOIL_MOISTURE);
        assertThat(created.getDeviation()).isEqualTo(AlertDeviation.LOW);
        assertThat(created.getMeasuredValue()).isEqualByComparingTo("35.00");
        assertThat(created.getThresholdMin()).isEqualByComparingTo("40.00");
        assertThat(created.getThresholdMax()).isEqualByComparingTo("55.00");
        assertThat(created.getOccurredAt()).isEqualTo(MEASURED_AT);
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void singleSpikeDoesNotCreateAlert() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("45.00", "44.00", "20.00");
        givenNoActiveAlert();

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.UNCHANGED);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void activeAlertIsNotDuplicatedWhileDeviationContinues() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("34.00", "33.00", "35.00");
        givenActiveAlert(AlertDeviation.LOW);

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.UNCHANGED);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void valueInsideHysteresisBandKeepsAlertActive() {
        givenActivePlant();
        givenGrowthProfile();
        // 하한 40 은 넘겼지만 복귀 기준 41.5 에는 못 미친다. 여기서 해제하면 재생성이 반복된다.
        givenSamples("41.00", "40.60", "41.20");
        Alert active = givenActiveAlert(AlertDeviation.LOW);

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.UNCHANGED);
        assertThat(active.isActive()).isTrue();
        verify(alertRepository, never()).save(any());
    }

    @Test
    void recoveryBeyondHysteresisBandResolvesAlert() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("43.00", "42.00", "44.00");
        Alert active = givenActiveAlert(AlertDeviation.LOW);

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.RESOLVED);
        assertThat(active.isActive()).isFalse();
        assertThat(active.getResolvedAt()).isEqualTo(LocalDateTime.of(2026, 7, 26, 0, 40));
        verify(alertRepository, never()).save(any());
    }

    @Test
    void oppositeDeviationResolvesAndOpensNewAlert() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("60.00", "61.00", "59.00");
        Alert active = givenActiveAlert(AlertDeviation.LOW);

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.SWITCHED);
        assertThat(active.isActive()).isFalse();

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getDeviation()).isEqualTo(AlertDeviation.HIGH);
        assertThat(captor.getValue().getMeasuredValue()).isEqualByComparingTo("60.00");
    }

    @Test
    void openedAlertIsPublishedSoThatPushCanBeSent() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("35.00", "41.00", "34.00");
        givenNoActiveAlert();

        alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE);

        ArgumentCaptor<Alert> savedCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(savedCaptor.capture());
        ArgumentCaptor<AlertOpenedEvent> eventCaptor = ArgumentCaptor.forClass(AlertOpenedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        AlertOpenedEvent event = eventCaptor.getValue();
        // 저장된 알림과 같은 식별자여야 앱이 푸시를 눌렀을 때 그 알림을 열 수 있다.
        assertThat(event.alertId()).isEqualTo(savedCaptor.getValue().getId());
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.plantId()).isEqualTo(PLANT_ID);
        assertThat(event.plantNickname()).isEqualTo(NICKNAME);
        assertThat(event.metricType()).isEqualTo(AlertMetricType.SOIL_MOISTURE);
        assertThat(event.deviation()).isEqualTo(AlertDeviation.LOW);
    }

    @Test
    void recoveryIsNotPublished() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("43.00", "42.00", "44.00");
        givenActiveAlert(AlertDeviation.LOW);

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.RESOLVED);
        // 정상 복귀까지 알리면 기준선 근처를 오갈 때 푸시가 두 배로 늘어난다.
        verify(eventPublisher, never()).publishEvent(any(AlertOpenedEvent.class));
    }

    @Test
    void continuingDeviationIsNotPublishedAgain() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("34.00", "33.00", "35.00");
        givenActiveAlert(AlertDeviation.LOW);

        assertThat(alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE))
                .isEqualTo(AlertEvaluationResult.UNCHANGED);
        verify(eventPublisher, never()).publishEvent(any(AlertOpenedEvent.class));
    }

    @Test
    void switchedDeviationPublishesTheNewDirection() {
        givenActivePlant();
        givenGrowthProfile();
        givenSamples("60.00", "61.00", "59.00");
        givenActiveAlert(AlertDeviation.LOW);

        alertEvaluationService.evaluate(PLANT_ID, SensorType.SOIL_MOISTURE);

        ArgumentCaptor<AlertOpenedEvent> eventCaptor = ArgumentCaptor.forClass(AlertOpenedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().deviation()).isEqualTo(AlertDeviation.HIGH);
    }

    private void givenActivePlant() {
        Plant plant = mock(Plant.class);
        when(plant.getStatus()).thenReturn(PlantStatus.ACTIVE);
        when(plantRepository.findById(PLANT_ID)).thenReturn(Optional.of(plant));
        org.mockito.Mockito.lenient().when(plant.getId()).thenReturn(PLANT_ID);
        org.mockito.Mockito.lenient().when(plant.getNickname()).thenReturn(NICKNAME);
        AppUser user = mock(AppUser.class);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(USER_ID);
        org.mockito.Mockito.lenient().when(plant.getUser()).thenReturn(user);
    }

    private void givenGrowthProfile() {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profile.getSoilMoistureMinPct()).thenReturn(new BigDecimal("40.00"));
        when(profile.getSoilMoistureMaxPct()).thenReturn(new BigDecimal("55.00"));
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }

    /** 최신순으로 전달한다. 첫 번째 값의 측정 시각이 발생 시각이 된다. */
    private void givenSamples(String... values) {
        List<SensorReading> readings = List.of(values).stream()
                .map(value -> SensorReading.create(
                        PLANT_ID,
                        "30000000-0000-0000-0000-0000000000cc",
                        "40000000-0000-0000-0000-0000000000dd",
                        "message-" + value,
                        SensorType.SOIL_MOISTURE,
                        new BigDecimal(value),
                        SensorUnit.PERCENT,
                        MEASURED_AT,
                        MEASURED_AT
                ))
                .toList();
        when(sensorReadingRepository
                .findByPlantIdAndSensorTypeAndQualityAndMeasuredAtGreaterThanEqualOrderByMeasuredAtDesc(
                        eq(PLANT_ID),
                        eq(SensorType.SOIL_MOISTURE),
                        eq(SensorQuality.GOOD),
                        any(LocalDateTime.class),
                        any(Pageable.class)))
                .thenReturn(readings);
    }

    private void givenNoActiveAlert() {
        when(alertRepository.findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                PLANT_ID,
                AlertMetricType.SOIL_MOISTURE))
                .thenReturn(Optional.empty());
    }

    private Alert givenActiveAlert(AlertDeviation deviation) {
        Alert active = Alert.open(
                USER_ID,
                PLANT_ID,
                AlertMetricType.SOIL_MOISTURE,
                deviation,
                new BigDecimal("34.00"),
                new BigDecimal("40.00"),
                new BigDecimal("55.00"),
                MEASURED_AT.minusMinutes(1)
        );
        when(alertRepository.findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                PLANT_ID,
                AlertMetricType.SOIL_MOISTURE))
                .thenReturn(Optional.of(active));
        return active;
    }
}
