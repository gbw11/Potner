package com.potner.happiness.application;

import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.bloom.domain.PlantBloomRepository;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDeviceType;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.domain.ScoreReason;
import com.potner.happiness.dto.StatusReportResponse;
import com.potner.light.domain.PlantDailyLight;
import com.potner.light.domain.PlantDailyLightRepository;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusReportServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 26);

    /** 한국 시간 07-26 00:00 은 UTC 07-25 15:00 이다. UTC 자정으로 끊으면 9시간 밀린다. */
    private static final LocalDateTime FROM_UTC = LocalDateTime.of(2026, 7, 25, 15, 0);
    private static final LocalDateTime TO_UTC = FROM_UTC.plusSeconds(86399);

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private PlantBloomRepository bloomRepository;

    @Mock
    private PlantDailyLightRepository dailyLightRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private DeviceCommandRepository deviceCommandRepository;

    private StatusReportService statusReportService;

    @BeforeEach
    void setUp() {
        statusReportService = new StatusReportService(
                plantRepository,
                alertRepository,
                bloomRepository,
                dailyLightRepository,
                sensorReadingRepository,
                deviceCommandRepository,
                new HappinessProperties(
                        true,
                        new BigDecimal("0.5"),
                        30,
                        IotDeviceType.JETSON_ORIN,
                        5,
                        5
                ),
                new SensorQueryProperties("+09:00", 15, 7, 90)
        );
        givenOwnedPlant();
    }

    @Test
    void aDayWithoutAlertsScoresFull() {
        givenMeasurements(144);
        givenAlerts();
        givenNoBloom();

        StatusReportResponse report = statusReportService.getReport(USER_ID, PLANT_ID, DATE);

        assertThat(report.happinessScore()).isEqualTo(100);
        assertThat(report.adjustments()).isEmpty();
    }

    @Test
    void oneAlertMakesNinetyFive() {
        // 디자인의 상태 리포트가 95 를 보여준다. 알림 하나 있던 날이 그 값이 되어야 한다.
        givenMeasurements(144);
        givenAlerts(AlertMetricType.SOIL_MOISTURE);
        givenNoBloom();

        StatusReportResponse report = statusReportService.getReport(USER_ID, PLANT_ID, DATE);

        assertThat(report.happinessScore()).isEqualTo(95);
        assertThat(report.adjustments())
                .containsExactly(new StatusReportResponse.ScoreAdjustment(
                        ScoreReason.SOIL_MOISTURE_ALERT, -5));
    }

    @Test
    void lightAndPhotoperiodAreCountedThroughAlertsOnly() {
        // 누적 광량과 일조 시간 이상도 alert 에 기록된다. 광량 판정을 따로 또 깎으면 같은
        // 사실로 두 번 감점한다.
        givenMeasurements(144);
        givenAlerts(AlertMetricType.DAILY_LIGHT, AlertMetricType.PHOTOPERIOD);
        givenNoBloom();
        givenDailyLight("6.5");

        StatusReportResponse report = statusReportService.getReport(USER_ID, PLANT_ID, DATE);

        assertThat(report.happinessScore()).isEqualTo(90);
        assertThat(report.adjustments()).extracting(StatusReportResponse.ScoreAdjustment::reason)
                .containsExactly(ScoreReason.DAILY_LIGHT_ALERT, ScoreReason.PHOTOPERIOD_ALERT);
    }

    @Test
    void sameMetricOpeningTwiceIsDeductedOnce() {
        // 기준선 근처에서 값이 오가면 같은 지표로 열렸다 닫혔다를 반복할 수 있다. 그때마다
        // 깎으면 하루에 한 지표로 점수가 0 까지 내려간다.
        givenMeasurements(144);
        givenAlerts(AlertMetricType.TEMPERATURE, AlertMetricType.TEMPERATURE, AlertMetricType.TEMPERATURE);
        givenNoBloom();

        assertThat(statusReportService.getReport(USER_ID, PLANT_ID, DATE).happinessScore())
                .isEqualTo(95);
    }

    @Test
    void bloomingAddsPoints() {
        givenMeasurements(144);
        givenAlerts(AlertMetricType.HUMIDITY);
        when(bloomRepository.existsByPlantIdAndBloomDate(PLANT_ID, DATE)).thenReturn(true);

        StatusReportResponse report = statusReportService.getReport(USER_ID, PLANT_ID, DATE);

        assertThat(report.happinessScore()).isEqualTo(100);
        assertThat(report.adjustments()).extracting(StatusReportResponse.ScoreAdjustment::reason)
                .contains(ScoreReason.BLOOMED);
    }

    @Test
    void scoreNeverGoesAboveFullOrBelowZero() {
        givenMeasurements(144);
        givenAlerts();
        when(bloomRepository.existsByPlantIdAndBloomDate(PLANT_ID, DATE)).thenReturn(true);

        // 알림이 없는데 개화하면 105 가 되지만 천장이 100 이다.
        assertThat(statusReportService.getReport(USER_ID, PLANT_ID, DATE).happinessScore())
                .isEqualTo(100);
    }

    @Test
    void aDayWithoutMeasurementsHasNoScoreRatherThanZero() {
        // 알림도 없으니 100 점이 되는데, 그러면 기기가 꺼져 있던 하루가 완벽한 하루로 보인다.
        // 0 점으로 주면 "최악의 하루" 와 구별할 수 없다.
        givenMeasurements(0);
        givenDailyLight("3.0");

        StatusReportResponse report = statusReportService.getReport(USER_ID, PLANT_ID, DATE);

        assertThat(report.happinessScore()).isNull();
        assertThat(report.adjustments()).isEmpty();
        // 광량 집계가 있으면 그건 그대로 보여준다.
        assertThat(report.lightHours()).isEqualByComparingTo("3.0");
    }

    @Test
    void dayBoundaryUsesTheServiceTimezone() {
        givenMeasurements(144);
        givenAlerts();
        givenNoBloom();

        statusReportService.getReport(USER_ID, PLANT_ID, DATE);

        // UTC 자정으로 끊으면 한국 시간 오전 9시 전의 알림이 전날로 밀린다.
        org.mockito.Mockito.verify(alertRepository)
                .findAllByPlantIdAndOccurredAtBetweenOrderByOccurredAtAsc(PLANT_ID, FROM_UTC, TO_UTC);
    }

    @Test
    void lightHoursIsNullBeforeTheNightlyAggregation() {
        // 광량은 하루가 끝난 뒤 새벽에 확정된다. 오늘 것은 아직 없다.
        givenMeasurements(144);
        givenAlerts();
        givenNoBloom();
        when(dailyLightRepository.findByPlantIdAndLightDate(PLANT_ID, DATE))
                .thenReturn(Optional.empty());

        StatusReportResponse report = statusReportService.getReport(USER_ID, PLANT_ID, DATE);

        assertThat(report.lightHours()).isNull();
        // 광량 집계가 없다고 점수를 깎지는 않는다. 배치가 아직 안 돈 것과 데이터가 없는 것을
        // 구별할 수 없기 때문이다.
        assertThat(report.happinessScore()).isEqualTo(100);
    }

    @Test
    void wateredMlIsNotAvailableYet() {
        givenMeasurements(144);
        givenAlerts();
        givenNoBloom();

        assertThat(statusReportService.getReport(USER_ID, PLANT_ID, DATE).wateredMl()).isNull();
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> statusReportService.getReport(USER_ID, PLANT_ID, DATE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.PLANT_NOT_FOUND));
    }

    @Test
    void missingDateIsRejected() {
        assertThatThrownBy(() -> statusReportService.getReport(USER_ID, PLANT_ID, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private void givenOwnedPlant() {
        lenient().when(plantRepository
                        .findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
        lenient().when(dailyLightRepository.findByPlantIdAndLightDate(PLANT_ID, DATE))
                .thenReturn(Optional.empty());
    }

    private void givenMeasurements(long count) {
        when(sensorReadingRepository.countByPlantIdAndMeasuredAtBetween(anyString(), any(), any()))
                .thenReturn(count);
    }

    private void givenAlerts(AlertMetricType... metricTypes) {
        List<Alert> alerts = Arrays.stream(metricTypes).map(metricType -> {
            Alert alert = mock(Alert.class);
            lenient().when(alert.getMetricType()).thenReturn(metricType);
            return alert;
        }).toList();
        lenient().when(alertRepository.findAllByPlantIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        anyString(), any(), any()))
                .thenReturn(alerts);
    }

    private void givenNoBloom() {
        lenient().when(bloomRepository.existsByPlantIdAndBloomDate(PLANT_ID, DATE))
                .thenReturn(false);
    }

    private void givenDailyLight(String lightHours) {
        PlantDailyLight dailyLight = mock(PlantDailyLight.class);
        lenient().when(dailyLight.getLightHours()).thenReturn(new BigDecimal(lightHours));
        lenient().when(dailyLightRepository.findByPlantIdAndLightDate(PLANT_ID, DATE))
                .thenReturn(Optional.of(dailyLight));
    }
}
