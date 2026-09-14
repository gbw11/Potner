package com.potner.happiness.application;

import com.potner.bloom.domain.PlantBloomRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.device.domain.RobotState;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.HappinessGrade;
import com.potner.happiness.domain.PlantExpression;
import com.potner.happiness.dto.HappinessResponse;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.application.SensorThresholds;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 로봇 디스플레이에 그릴 표정을 정한다.
 *
 * <p>저장하지 않는다. 판정에 쓰는 값이 전부 다른 테이블에 이미 있어서 부를 때마다 계산하면
 * 된다. 규칙이 바뀌어도 과거 데이터를 다시 계산할 필요가 없다.
 *
 * <p>규칙은 두 층이다. 온·습도가 바탕을 정하고, 행동이 그 위를 덮는다. 행동 쪽이 이긴다.
 * 우선순위가 높은 것부터:
 *
 * <ol>
 *   <li>매우행복 — 급수·송풍 중, 사용자 반김 (로봇 상태를 받은 뒤에 붙는다)</li>
 *   <li>행복 — 오늘 개화, 충분한 광량, 급수 직후 (급수는 나중에 붙는다)</li>
 *   <li>우울 — 온도 또는 습도가 기준 이탈</li>
 *   <li>기본 — 그 외</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class HappinessService {

    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final PlantBloomRepository bloomRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final RobotRepository robotRepository;
    private final HappinessMessageFactory messageFactory;
    private final HappinessProperties properties;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public HappinessService(
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            SensorReadingRepository sensorReadingRepository,
            PlantBloomRepository bloomRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            RobotRepository robotRepository,
            HappinessMessageFactory messageFactory,
            HappinessProperties properties,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.bloomRepository = bloomRepository;
        this.assignmentRepository = assignmentRepository;
        this.robotRepository = robotRepository;
        this.messageFactory = messageFactory;
        this.properties = properties;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    /**
     * 홈 화면이 쓰는 현재 상태다. 등급·문구와 표정이 한 판정에서 나온다.
     *
     * <p>등급은 기준을 벗어난 지표 수로 정하고 표정은 지금 하는 행동까지 본다. 물을 마시는 중이면
     * 표정은 매우행복이지만 온도가 나쁘면 등급은 그대로 나쁘다. 표정은 사용자에게 감정을
     * 전하는 것이고 등급은 무엇을 확인해야 하는지 알리는 것이라 섞으면 둘 다 흐려진다.
     */
    public HappinessResponse getHappiness(String userId, String plantId) {
        requireOwnedPlant(userId, plantId);

        Optional<PlantGrowthProfile> profile = profileRepository.findByPlantId(plantId);
        List<AbnormalMetric> abnormalMetrics = profile
                .map(found -> findAbnormalMetrics(plantId, GrowthProfileValues.from(found)))
                .orElse(List.of());
        // 프로필이 없거나 신선한 측정값이 하나도 없으면 판정 근거가 없다. GOOD 으로 처리하면
        // 기기가 꺼져 있는 동안 "아주 좋아요" 를 보여주게 된다.
        HappinessGrade grade = profile.isEmpty() || !hasAnyFreshReading(plantId)
                ? HappinessGrade.UNKNOWN
                : gradeOf(abnormalMetrics.size());

        PlantExpressionState state = judge(plantId);
        HappinessMessageFactory.HappinessMessage message =
                messageFactory.create(grade, abnormalMetrics, state.reason());

        return new HappinessResponse(
                plantId,
                grade,
                message.headline(),
                message.detail(),
                abnormalMetrics.stream()
                        .map(metric -> new HappinessResponse.AbnormalMetricResponse(
                                metric.sensorType(), metric.status()))
                        .toList(),
                state.expression(),
                state.reason(),
                state.baseline()
        );
    }

    private HappinessGrade gradeOf(int abnormalCount) {
        return switch (abnormalCount) {
            case 0 -> HappinessGrade.GOOD;
            case 1 -> HappinessGrade.FAIR;
            default -> HappinessGrade.POOR;
        };
    }

    /**
     * 기준을 벗어난 지표를 모은다.
     *
     * <p>토양 수분을 포함한다. 표정의 바탕은 온·습도만 보지만 홈 화면은 "수분" 을 카드로
     * 보여주고 문구도 물을 언급하므로, 흙이 마른 것을 등급에서 빼면 화면과 어긋난다.
     *
     * <p>조도는 빠진다. 밤에 0 lux 가 정상이라 순간값으로 판정하지 않는다. 광량은 하루 누적으로
     * 판정하며 그 결과는 일별 점수에 들어간다.
     */
    private List<AbnormalMetric> findAbnormalMetrics(String plantId, GrowthProfileValues values) {
        List<AbnormalMetric> abnormal = new ArrayList<>();
        for (SensorType sensorType : List.of(
                SensorType.SOIL_MOISTURE, SensorType.TEMPERATURE, SensorType.HUMIDITY)) {
            findFreshReading(plantId, sensorType).ifPresent(reading -> {
                SensorStatus status = SensorThresholds.of(sensorType, values)
                        .evaluate(reading.getMeasuredValue());
                if (status == SensorStatus.LOW || status == SensorStatus.HIGH) {
                    abnormal.add(new AbnormalMetric(sensorType, status));
                }
            });
        }
        return abnormal;
    }

    private boolean hasAnyFreshReading(String plantId) {
        return Arrays.stream(SensorType.values())
                .anyMatch(sensorType -> findFreshReading(plantId, sensorType).isPresent());
    }

    /**
     * 표정을 판정한다. 소유권을 확인하지 않는다. 호출자가 사용자 요청이 아니라 주기 작업일 수
     * 있기 때문이다.
     *
     * <p>생육 프로필이 없으면 판정할 기준이 없다. 예외를 던지지 않고 기본 표정으로 둔다. 주기
     * 작업이 여러 식물을 도는 중에 한 건의 예외로 멈추면 안 된다.
     */
    public PlantExpressionState judge(String plantId) {
        Optional<PlantGrowthProfile> profile = profileRepository.findByPlantId(plantId);
        if (profile.isEmpty()) {
            return PlantExpressionState.of(PlantExpression.NEUTRAL, ExpressionReason.NONE);
        }
        GrowthProfileValues values = GrowthProfileValues.from(profile.get());
        PlantExpressionState baseline = judgeBaseline(plantId, values);

        // 부스트가 바탕을 이긴다. 환경이 나빠도 물을 마시는 순간은 기뻐야 사용자가 자기
        // 행동의 결과를 읽을 수 있다. 지금 하고 있는 일이 가장 강하다.
        Optional<RobotState> robotState = findRobotState(plantId);
        if (robotState.filter(state -> state == RobotState.SERVICING).isPresent()) {
            return baseline.boostedTo(PlantExpression.VERY_HAPPY, ExpressionReason.WATERING);
        }
        if (robotState.filter(state -> state == RobotState.GREETING).isPresent()) {
            return baseline.boostedTo(PlantExpression.VERY_HAPPY, ExpressionReason.GREETING);
        }
        if (bloomRepository.existsByPlantIdAndBloomDate(plantId, serviceToday())) {
            return baseline.boostedTo(PlantExpression.HAPPY, ExpressionReason.BLOOMED);
        }
        if (isInEnoughLight(plantId, values)) {
            return baseline.boostedTo(PlantExpression.HAPPY, ExpressionReason.SUNLIGHT);
        }
        return baseline;
    }

    /**
     * 이 식물을 담당하는 로봇의 현재 상태다.
     *
     * <p>배정이 없으면 비어 있다. {@code SERVICING} 이 급수와 송풍을 함께 가리키는데 표정은 둘
     * 다 같으므로 구별할 필요가 없다. 이유는 {@code WATERING} 하나로 보낸다.
     */
    private Optional<RobotState> findRobotState(String plantId) {
        return assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(plantId)
                .flatMap(assignment -> robotRepository.findById(assignment.getRobotId()))
                .map(Robot::getCurrentState);
    }

    /**
     * 온·습도로 바탕 표정을 정한다.
     *
     * <p>신선한 측정값이 없으면 기본이다. 오래된 값으로 우울을 표시하면 이미 해결된 문제를 계속
     * 보여주게 되고, 로봇이 꺼져 있던 동안의 값으로 표정을 단정하게 된다.
     */
    private PlantExpressionState judgeBaseline(String plantId, GrowthProfileValues values) {
        if (isOutOfRange(plantId, SensorType.TEMPERATURE, values)) {
            return PlantExpressionState.of(PlantExpression.SAD, ExpressionReason.TEMPERATURE);
        }
        if (isOutOfRange(plantId, SensorType.HUMIDITY, values)) {
            return PlantExpressionState.of(PlantExpression.SAD, ExpressionReason.HUMIDITY);
        }
        return PlantExpressionState.of(PlantExpression.NEUTRAL, ExpressionReason.NONE);
    }

    private boolean isOutOfRange(String plantId, SensorType sensorType, GrowthProfileValues values) {
        Optional<SensorReading> reading = findFreshReading(plantId, sensorType);
        if (reading.isEmpty()) {
            return false;
        }
        SensorStatus status = SensorThresholds.of(sensorType, values)
                .evaluate(reading.get().getMeasuredValue());
        return status == SensorStatus.LOW || status == SensorStatus.HIGH;
    }

    /**
     * 지금 충분히 밝은 곳에 있는지 본다.
     *
     * <p>목표 조도를 그대로 쓰지 않는다. 시드가 야외 기준이라 바질 발아기가 10,000 lux 인데
     * 실내는 창가라도 1,000~5,000 lux 여서 표정이 영영 나오지 않는다. 형광등은 300~500 lux 라
     * 절반 기준으로도 넘지 못하므로 실내등을 햇볕으로 오인하지는 않는다.
     *
     * <p>누적 광량을 쓰지 않는 이유는 그것이 하루가 끝나야 확정되기 때문이다. 표정은 지금을
     * 보여줘야 한다.
     */
    private boolean isInEnoughLight(String plantId, GrowthProfileValues values) {
        BigDecimal target = values.illuminanceTargetLux();
        if (target == null) {
            return false;
        }
        return findFreshReading(plantId, SensorType.ILLUMINANCE)
                .map(reading -> reading.getMeasuredValue()
                        .compareTo(target.multiply(properties.sunlightLuxRatio())) >= 0)
                .orElse(false);
    }

    private Optional<SensorReading> findFreshReading(String plantId, SensorType sensorType) {
        LocalDateTime staleBefore = nowUtc()
                .minusMinutes(sensorQueryProperties.freshnessThresholdMinutes());
        return sensorReadingRepository
                .findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                        plantId,
                        sensorType,
                        SensorQuality.GOOD
                )
                .filter(reading -> !reading.getMeasuredAt().isBefore(staleBefore));
    }

    /** 개화 날짜와 같은 서비스 타임존 기준이어야 오늘 핀 꽃이 오늘로 잡힌다. */
    private LocalDate serviceToday() {
        return nowUtc()
                .plusSeconds(sensorQueryProperties.zoneOffsetSeconds())
                .toLocalDate();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
