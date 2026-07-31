package com.potner.diary.application;

import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.light.domain.DailyLightStatus;
import com.potner.light.domain.PlantDailyLight;
import com.potner.light.domain.PlantDailyLightRepository;
import com.potner.photo.domain.PhotoSource;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 그날 무슨 일이 있었는지 모은다. 일기 프롬프트의 유일한 사실 출처다. */
@Component
public class PlantDaySummaryReader {

    private static final int SECONDS_PER_DAY = 86_400;

    private final PlantRepository plantRepository;
    private final AlertRepository alertRepository;
    private final PlantDailyLightRepository dailyLightRepository;
    private final PlantPhotoRepository photoRepository;
    private final SensorQueryProperties sensorQueryProperties;

    public PlantDaySummaryReader(
            PlantRepository plantRepository,
            AlertRepository alertRepository,
            PlantDailyLightRepository dailyLightRepository,
            PlantPhotoRepository photoRepository,
            SensorQueryProperties sensorQueryProperties
    ) {
        this.plantRepository = plantRepository;
        this.alertRepository = alertRepository;
        this.dailyLightRepository = dailyLightRepository;
        this.photoRepository = photoRepository;
        this.sensorQueryProperties = sensorQueryProperties;
    }

    /**
     * 활성 식물이면 그날 관찰을 모아 준다. 삭제됐거나 비활성이면 비어 있다.
     *
     * <p>여기서 트랜잭션을 닫는 것이 중요하다. {@code Plant} 의 종·사용자 연관이 지연 로딩이라
     * 트랜잭션 밖에서 만지면 터지고, 반대로 모델 호출까지 트랜잭션에 넣으면 응답을 기다리는
     * 몇 초 동안 커넥션을 붙들게 된다. 필요한 것을 전부 값으로 꺼내 돌려준다.
     */
    @Transactional(readOnly = true)
    public Optional<PlantDaySummary> read(String plantId, LocalDate diaryDate) {
        Plant plant = plantRepository.findById(plantId)
                .filter(candidate -> candidate.getStatus() == PlantStatus.ACTIVE)
                .orElse(null);
        if (plant == null) {
            return Optional.empty();
        }
        return Optional.of(summarize(plant, diaryDate));
    }

    private PlantDaySummary summarize(Plant plant, LocalDate diaryDate) {
        LocalDateTime fromUtc = startOfDayUtc(diaryDate);
        List<String> alertLabels = alertRepository
                .findAllByPlantIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        plant.getId(),
                        fromUtc,
                        fromUtc.plusSeconds(SECONDS_PER_DAY - 1))
                .stream()
                .map(PlantDaySummaryReader::describe)
                .distinct()
                .toList();

        return new PlantDaySummary(
                plant.getNickname(),
                plant.getSpecies().getId(),
                plant.getSpecies().getName(),
                diaryDate,
                alertLabels,
                describeLight(plant.getId(), diaryDate),
                photoRepository.existsByPlantIdAndSourceAndPhotoDate(
                        plant.getId(),
                        PhotoSource.DEVICE,
                        diaryDate)
        );
    }

    /**
     * 알림을 사실 진술로 옮긴다.
     *
     * <p>푸시 문구를 재사용하지 않는다. 그쪽은 사용자에게 행동을 청하는 말("물을 주세요")이라
     * 그대로 넣으면 모델이 일기에 부탁을 섞는다. 여기서는 무슨 상태였는지만 말한다.
     */
    private static String describe(Alert alert) {
        // 물 부족은 측정값·기준이 없어 "기준보다 낮았다" 문형이 맞지 않는다. 문장을 통째로 준다.
        if (alert.getMetricType() == AlertMetricType.STATION_WATER_LOW) {
            return "급수 스테이션의 물이 부족했다";
        }
        return metricLabel(alert.getMetricType()) + " " + deviationLabel(alert.getDeviation());
    }

    private static String metricLabel(AlertMetricType metricType) {
        return switch (metricType) {
            case TEMPERATURE -> "주변 온도가";
            case HUMIDITY -> "주변 습도가";
            case SOIL_MOISTURE -> "토양 수분이";
            case DAILY_LIGHT -> "하루 누적 광량이";
            case PHOTOPERIOD -> "일조 시간이";
            // describe 가 먼저 걸러 여기 오지 않는다. 컴파일 총망라를 위해서만 존재한다.
            case STATION_WATER_LOW -> throw new IllegalArgumentException(
                    "STATION_WATER_LOW is described as a full sentence");
        };
    }

    private static String deviationLabel(AlertDeviation deviation) {
        return deviation == AlertDeviation.LOW ? "기준보다 낮았다" : "기준보다 높았다";
    }

    /**
     * 광량 판정 요약이다. 집계가 없으면 비어 있다.
     *
     * <p>일기 배치가 저녁에 돌기 때문에 그날 광량은 대개 아직 없다. 누적 광량은 하루가 끝나야
     * 확정되어 새벽 2시 배치가 전일분을 집계한다. 그래서 이 값이 채워지는 것은 예외적인
     * 경우이며, 없다고 해서 문제가 아니다.
     *
     * <p>데이터가 모자란 날도 알리지 않는다. {@code INSUFFICIENT_DATA} 를 그대로 넘기면 모델이
     * 그것을 사건처럼 서술한다. 사용자에게는 장치가 꺼져 있었다는 사실보다 조용한 하루로 읽히는
     * 편이 낫고, 결측은 포토 로그와 광량 화면이 따로 드러낸다.
     */
    private String describeLight(String plantId, LocalDate diaryDate) {
        PlantDailyLight light = dailyLightRepository
                .findByPlantIdAndLightDate(plantId, diaryDate)
                .orElse(null);
        if (light == null
                || light.getLightStatus() == DailyLightStatus.INSUFFICIENT_DATA
                || light.getLightStatus() == DailyLightStatus.NOT_APPLICABLE) {
            return null;
        }
        return "하루 빛의 양은 %s, 일조 시간은 %s".formatted(
                statusLabel(light.getLightStatus()),
                statusLabel(light.getPhotoperiodStatus())
        );
    }

    private static String statusLabel(DailyLightStatus status) {
        return switch (status) {
            case NORMAL -> "적당했다";
            case LOW -> "부족했다";
            case HIGH -> "많았다";
            case INSUFFICIENT_DATA, NOT_APPLICABLE -> "알 수 없다";
        };
    }

    /**
     * 서비스 타임존 하루의 시작을 UTC 로 옮긴다.
     * 조회 대상인 {@code occurred_at} 이 UTC 라서 경계를 여기서 맞춰야 한다.
     */
    private LocalDateTime startOfDayUtc(LocalDate date) {
        return date.atStartOfDay().minusSeconds(sensorQueryProperties.zoneOffsetSeconds());
    }
}
