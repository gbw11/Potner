package com.potner.diary.application;

import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.light.domain.DailyLightStatus;
import com.potner.light.domain.PlantDailyLight;
import com.potner.light.domain.PlantDailyLightRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.photo.domain.PhotoSource;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorHistoryBucket;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 그날 무슨 일이 있었는지 모은다. 일기 프롬프트의 유일한 사실 출처다. */
@Component
public class PlantDaySummaryReader {

    private static final int SECONDS_PER_DAY = 86_400;

    private final PlantRepository plantRepository;
    private final AlertRepository alertRepository;
    private final PlantDailyLightRepository dailyLightRepository;
    private final PlantPhotoRepository photoRepository;
    private final DeviceCommandRepository commandRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final SensorQueryProperties sensorQueryProperties;

    public PlantDaySummaryReader(
            PlantRepository plantRepository,
            AlertRepository alertRepository,
            PlantDailyLightRepository dailyLightRepository,
            PlantPhotoRepository photoRepository,
            DeviceCommandRepository commandRepository,
            SensorReadingRepository sensorReadingRepository,
            SensorQueryProperties sensorQueryProperties
    ) {
        this.plantRepository = plantRepository;
        this.alertRepository = alertRepository;
        this.dailyLightRepository = dailyLightRepository;
        this.photoRepository = photoRepository;
        this.commandRepository = commandRepository;
        this.sensorReadingRepository = sensorReadingRepository;
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
        List<Alert> alerts = alertRepository
                .findAllByPlantIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        plant.getId(),
                        fromUtc,
                        fromUtc.plusSeconds(SECONDS_PER_DAY - 1));
        List<String> alertLabels = alerts.stream()
                .map(PlantDaySummaryReader::describe)
                .distinct()
                .toList();
        List<AlertMetricType> alertedMetrics = alerts.stream()
                .map(Alert::getMetricType)
                .distinct()
                .toList();

        return new PlantDaySummary(
                plant.getNickname(),
                plant.getSpecies().getId(),
                plant.getSpecies().getName(),
                diaryDate,
                alertLabels,
                describeCare(plant.getId(), fromUtc),
                describeSteadyMetrics(plant.getId(), fromUtc, alertedMetrics),
                describeLight(plant.getId(), diaryDate),
                photoRepository.existsByPlantIdAndSourceAndPhotoDate(
                        plant.getId(),
                        PhotoSource.DEVICE,
                        diaryDate)
        );
    }

    /**
     * 그날 실제로 받은 돌봄이다.
     *
     * <p><strong>회신으로 확인된 것만 담는다.</strong> 발행만 하고 장치가 실패했거나 회신이 없던
     * 명령은 아무 일도 일어나지 않은 것과 같은데, 그것을 근거로 주면 일기가 받지도 않은 물을
     * 받았다고 쓴다. 급수는 실측량이 있는 것만, 나머지는 {@code OK} 만 센다.
     *
     * <p>식물의 시점으로 적는다. 일기를 쓰는 주체가 식물이라 "로봇이 스테이션으로 갔다" 는
     * 시점이 맞지 않는다 — 식물이 겪은 것은 물을 받고 바람을 쐰 일이다.
     *
     * <p>명령 하나하나를 나열하지 않고 종류별로 묶는다. 하루 여섯 번 도는 환기를 여섯 줄로 주면
     * 모델이 그 반복을 사건으로 읽어 일기가 송풍 이야기로 뒤덮인다.
     */
    private List<String> describeCare(String plantId, LocalDateTime fromUtc) {
        LocalDateTime toUtc = fromUtc.plusSeconds(SECONDS_PER_DAY - 1);
        List<DeviceCommand> commands = commandRepository
                .findAllByPlantIdAndIssuedAtBetweenOrderByIssuedAtDesc(plantId, fromUtc, toUtc);

        List<String> labels = new ArrayList<>();

        // 양을 적지 않는다. 일기 화면의 상태 리포트가 이미 실측 급수량을 숫자로 보여주므로
        // 본문에서 또 말할 이유가 없고, 식물이 자기 일기에 밀리리터를 적는 것도 어색하다.
        BigDecimal watered = commands.stream()
                .filter(command -> command.getCommandType() == DeviceCommandType.WATER)
                .map(DeviceCommand::getDispensedMl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (watered.signum() > 0) {
            labels.add("물을 받았다");
        }

        long fanRuns = countOk(commands, DeviceCommandType.FAN);
        if (fanRuns > 0) {
            labels.add("바람을 %d번 쐬었다".formatted(fanRuns));
        }

        boolean movedToSunlight = commands.stream()
                .anyMatch(command -> command.getCommandType() == DeviceCommandType.NAVIGATE
                        && command.getStatus() == DeviceCommandStatus.OK
                        && command.getDestination() == RobotLocationType.SUNLIGHT);
        if (movedToSunlight) {
            labels.add("햇빛이 잘 드는 자리로 옮겨졌다");
        }
        return labels;
    }

    /**
     * 그날 별일 없었던 지표다.
     *
     * <p>알림은 기준을 벗어났을 때만 생긴다. 그래서 아무 문제 없던 날에는 쓸 것이 남지 않아
     * 여섯 종이 모두 같은 한 줄을 각자 말투로 반복하게 된다. 그 빈자리를 메운다.
     *
     * <p><strong>수치를 적지 않는다.</strong> 일기 화면의 상태 리포트가 숫자를 맡고 본문은
     * 이야기를 맡는다. 근거에 숫자를 넣으면 모델이 그대로 옮겨 적어("습도 53~88%") 서정형
     * 페르소나의 문체가 깨진다. 지어내지 못하게 하려면 주지 않는 편이 확실하다.
     *
     * <p>같은 지표의 알림이 있으면 넣지 않는다. 알림이 "벗어났다" 를 이미 말하므로 여기서
     * "벗어나지 않았다" 를 또 말하면 서로 어긋난다.
     */
    private List<String> describeSteadyMetrics(
            String plantId,
            LocalDateTime fromUtc,
            List<AlertMetricType> alertedMetrics
    ) {
        List<String> labels = new ArrayList<>();
        for (SensorType type : List.of(SensorType.TEMPERATURE, SensorType.HUMIDITY)) {
            if (alertedMetrics.contains(alertMetricOf(type)) || !hasReadings(plantId, fromUtc, type)) {
                continue;
            }
            // 알림 문구와 짝이 되게 쓴다. "추웠다/더웠다" 옆에 "기준을 벗어나지 않았다" 가
            // 오면 한쪽은 느낌이고 한쪽은 판정이라 목록의 결이 갈린다.
            labels.add(switch (type) {
                case TEMPERATURE -> "춥지도 덥지도 않았다";
                case HUMIDITY -> "공기가 메마르지도 눅눅하지도 않았다";
                default -> throw new IllegalStateException("Unhandled steady metric: " + type);
            });
        }
        return labels;
    }

    private static AlertMetricType alertMetricOf(SensorType type) {
        return switch (type) {
            case TEMPERATURE -> AlertMetricType.TEMPERATURE;
            case HUMIDITY -> AlertMetricType.HUMIDITY;
            case SOIL_MOISTURE -> AlertMetricType.SOIL_MOISTURE;
            case ILLUMINANCE -> AlertMetricType.DAILY_LIGHT;
        };
    }

    /**
     * 그날 측정값이 있었는지 본다. 장치가 꺼져 있던 날에 "벗어난 적 없었다" 고 말하면 안 된다 —
     * 잰 적이 없는 것이지 안정적이었던 것이 아니다.
     */
    private boolean hasReadings(String plantId, LocalDateTime fromUtc, SensorType type) {
        List<SensorHistoryBucket> buckets = sensorReadingRepository.aggregateHistory(
                plantId,
                type.name(),
                fromUtc,
                fromUtc.plusSeconds(SECONDS_PER_DAY),
                SECONDS_PER_DAY,
                sensorQueryProperties.zoneOffsetSeconds());
        return !buckets.isEmpty() && buckets.getFirst().getSampleCount() >= 2;
    }

    private static long countOk(List<DeviceCommand> commands, DeviceCommandType type) {
        return commands.stream()
                .filter(command -> command.getCommandType() == type)
                .filter(command -> command.getStatus() == DeviceCommandStatus.OK)
                .count();
    }

    /**
     * 알림을 사실 진술로 옮긴다.
     *
     * <p>푸시 문구를 재사용하지 않는다. 그쪽은 사용자에게 행동을 청하는 말("물을 주세요")이라
     * 그대로 넣으면 모델이 일기에 부탁을 섞는다. 여기서는 무슨 상태였는지만 말한다.
     */
    private static String describe(Alert alert) {
        boolean low = alert.getDeviation() == AlertDeviation.LOW;
        return switch (alert.getMetricType()) {
            case TEMPERATURE -> low ? "추웠다" : "더웠다";
            case HUMIDITY -> low ? "공기가 메말랐다" : "공기가 눅눅했다";
            case SOIL_MOISTURE -> low ? "흙이 말랐다" : "흙이 너무 젖어 있었다";
            case DAILY_LIGHT -> low ? "빛이 모자랐다" : "빛이 너무 강했다";
            case PHOTOPERIOD -> low ? "해를 짧게 봤다" : "해를 너무 오래 봤다";
            // 설비 상태는 식물이 느끼는 것이 아니라 주변에서 일어난 일이다.
            case STATION_WATER_LOW -> "물을 받아 오는 곳이 비어 있었다";
            case DRAINAGE_TRAY -> "받침에 물이 고여 있었다";
        };
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
