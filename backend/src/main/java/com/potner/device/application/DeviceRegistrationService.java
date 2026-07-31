package com.potner.device.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.DeviceUploadToken;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotConnectionState;
import com.potner.device.domain.RobotRepository;
import com.potner.device.dto.DeviceUploadTokenResponse;
import com.potner.device.dto.IotDeviceResponse;
import com.potner.device.dto.PlantAssignmentResponse;
import com.potner.device.dto.RegisterIotDeviceRequest;
import com.potner.device.dto.RegisterRobotRequest;
import com.potner.device.dto.RegisteredRobotResponse;
import com.potner.device.dto.RobotListResponse;
import com.potner.device.dto.RobotRegistrationResponse;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 로봇과 하위 IoT 장치를 등록하고 식물에 배정한다.
 *
 * <p>이 기능이 없으면 측정값을 저장할 수 없다. 유입 경로가
 * {@code device_uid → iot_device → robot → plant_device_assignment → plant} 이므로
 * 세 행이 모두 있어야 MQTT 로 들어온 값이 식물에 연결된다.
 *
 * <p>{@code device_uid} 는 사용자가 직접 입력한다. MQTT 토픽 세그먼트, 페이로드 deviceId,
 * Mosquitto 계정명과 같아야 하므로 등록 후에는 바꾸지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class DeviceRegistrationService {

    private final AppUserRepository appUserRepository;
    private final PlantRepository plantRepository;
    private final RobotRepository robotRepository;
    private final IotDeviceRepository iotDeviceRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final DeviceUploadToken uploadTokenIssuer;
    private final Clock clock;

    public DeviceRegistrationService(
            AppUserRepository appUserRepository,
            PlantRepository plantRepository,
            RobotRepository robotRepository,
            IotDeviceRepository iotDeviceRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            DeviceUploadToken uploadTokenIssuer,
            Clock clock
    ) {
        this.appUserRepository = appUserRepository;
        this.plantRepository = plantRepository;
        this.robotRepository = robotRepository;
        this.iotDeviceRepository = iotDeviceRepository;
        this.assignmentRepository = assignmentRepository;
        this.uploadTokenIssuer = uploadTokenIssuer;
        this.clock = clock;
    }

    /**
     * 로봇을 등록하고 업로드 토큰을 발급한다.
     *
     * <p>토큰 원문은 이 응답에서만 노출된다. 해시만 저장하므로 다시 조회할 수 없다.
     */
    @Transactional
    public RobotRegistrationResponse registerRobot(String userId, RegisterRobotRequest request) {
        AppUser user = findUser(userId);
        String deviceUid = request.deviceUid().trim();
        requireUnusedDeviceUid(deviceUid);

        Robot robot = Robot.register(user, deviceUid, request.name().trim());
        String uploadToken = uploadTokenIssuer.generate();
        robot.replaceUploadTokenHash(uploadTokenIssuer.hash(uploadToken));
        robotRepository.save(robot);

        // 방금 등록한 로봇이므로 하위 장치도 배정도 아직 없다.
        return new RobotRegistrationResponse(
                toResponse(robot, List.of(), null, Map.of()),
                uploadToken
        );
    }

    /**
     * 업로드 토큰을 다시 발급한다. 토큰을 잃어버렸거나 유출됐을 때 쓴다.
     * 이전 토큰은 즉시 무효가 되므로 라즈베리 설정도 함께 바꿔야 한다.
     */
    @Transactional
    public DeviceUploadTokenResponse reissueUploadToken(String userId, String robotId) {
        Robot robot = findOwnedRobot(userId, robotId);
        String uploadToken = uploadTokenIssuer.generate();
        robot.replaceUploadTokenHash(uploadTokenIssuer.hash(uploadToken));
        return new DeviceUploadTokenResponse(robot.getId(), uploadToken);
    }

    public RobotListResponse getRobots(String userId) {
        List<Robot> robots = robotRepository.findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(userId);
        if (robots.isEmpty()) {
            return new RobotListResponse(List.of());
        }

        Map<String, PlantDeviceAssignment> assignmentByRobotId = activeAssignmentsOf(robots);
        Map<String, String> plantNameById = plantNamesOf(assignmentByRobotId.values());

        List<RegisteredRobotResponse> responses = new ArrayList<>();
        for (Robot robot : robots) {
            List<IotDevice> devices = iotDeviceRepository
                    .findAllByRobotIdOrderByDeviceTypeAsc(robot.getId());
            responses.add(toResponse(
                    robot,
                    devices,
                    assignmentByRobotId.get(robot.getId()),
                    plantNameById
            ));
        }
        return new RobotListResponse(responses);
    }

    @Transactional
    public IotDeviceResponse registerIotDevice(
            String userId,
            String robotId,
            RegisterIotDeviceRequest request
    ) {
        Robot robot = findOwnedRobot(userId, robotId);
        String deviceUid = request.deviceUid().trim();
        requireUnusedDeviceUid(deviceUid);

        IotDevice device = iotDeviceRepository
                .save(IotDevice.register(robot, deviceUid, request.deviceType()));
        return IotDeviceResponse.from(device);
    }

    /**
     * 식물에 담당 로봇을 배정한다.
     *
     * <p>활성 배정은 식물·로봇당 하나여야 한다. DB의 생성 컬럼 UNIQUE 가 최종 안전망이지만,
     * 그 위반은 제약 예외로 드러나 원인을 알기 어려우므로 여기서 먼저 판별해 알맞은 오류를 낸다.
     */
    @Transactional
    public PlantAssignmentResponse assignRobot(String userId, String plantId, String robotId) {
        requireOwnedPlant(userId, plantId);
        Robot robot = findOwnedRobot(userId, robotId);

        if (activeAssignmentOfPlant(plantId).isPresent()) {
            throw new BusinessException(ErrorCode.PLANT_ALREADY_ASSIGNED);
        }
        if (assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robotId)
                .isPresent()) {
            throw new BusinessException(ErrorCode.ROBOT_ALREADY_ASSIGNED);
        }

        PlantDeviceAssignment assignment = assignmentRepository
                .save(PlantDeviceAssignment.assign(plantId, robotId, nowUtc()));
        return PlantAssignmentResponse.of(assignment, robot);
    }

    /**
     * 로봇과 하위 장치를 함께 해제한다. 같은 코드로 다른 계정이 새로 등록할 수 있게 된다.
     *
     * <p>물리 삭제가 아니다 — 측정 이력이 RESTRICT 로 참조하고 있어 지울 수 없고, 이력은 이전
     * 주인의 식물 기록이라 남는 것이 맞다. 활성 배정도 함께 해제해 로봇이 식물에서 풀린다.
     *
     * <p><strong>브로커 계정은 여기서 정리되지 않는다.</strong> mosquitto 계정은 device_uid 로
     * 수동 관리되므로, 소유권을 넘길 때는 비밀번호 재발급이 별도로 필요하다. 안 하면 이전
     * 주인이 새 주인의 토픽에 발행할 수 있다 (DEVICE-MQTT.md 12절).
     */
    @Transactional
    public void releaseRobot(String userId, String robotId) {
        Robot robot = findOwnedRobot(userId, robotId);
        LocalDateTime now = nowUtc();

        assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robotId)
                .ifPresent(assignment -> assignment.unassign(now));
        iotDeviceRepository
                .findAllByRobotIdOrderByDeviceTypeAsc(robotId)
                .forEach(device -> device.release(now));
        robot.release(now);
    }

    /** 행을 지우지 않고 해제 시각만 남긴다. 같은 식물에 다른 로봇을 다시 배정할 수 있다. */
    @Transactional
    public void unassignRobot(String userId, String plantId) {
        requireOwnedPlant(userId, plantId);
        PlantDeviceAssignment assignment = activeAssignmentOfPlant(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND));
        assignment.unassign(nowUtc());
    }

    private RegisteredRobotResponse toResponse(
            Robot robot,
            List<IotDevice> devices,
            PlantDeviceAssignment assignment,
            Map<String, String> plantNameById
    ) {
        RobotConnectionState connection = RobotConnectionState.derive(devices);
        String assignedPlantId = assignment == null ? null : assignment.getPlantId();
        return new RegisteredRobotResponse(
                robot.getId(),
                robot.getDeviceUid(),
                robot.getName(),
                connection.status(),
                connection.lastSeenAt(),
                robot.getBatteryPercent(),
                robot.getFirmwareVersion(),
                assignedPlantId,
                assignedPlantId == null ? null : plantNameById.get(assignedPlantId),
                assignment == null ? null : assignment.getAssignedAt(),
                devices.stream().map(IotDeviceResponse::from).toList()
        );
    }

    private Map<String, PlantDeviceAssignment> activeAssignmentsOf(List<Robot> robots) {
        Map<String, PlantDeviceAssignment> assignmentByRobotId = new HashMap<>();
        for (Robot robot : robots) {
            assignmentRepository
                    .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robot.getId())
                    .ifPresent(assignment -> assignmentByRobotId.put(robot.getId(), assignment));
        }
        return assignmentByRobotId;
    }

    /** 배정된 식물 이름을 한 번에 가져온다. 로봇마다 조회하지 않도록 묶는다. */
    private Map<String, String> plantNamesOf(Iterable<PlantDeviceAssignment> assignments) {
        List<String> plantIds = new ArrayList<>();
        assignments.forEach(assignment -> plantIds.add(assignment.getPlantId()));
        if (plantIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> plantNameById = new HashMap<>();
        for (Plant plant : plantRepository.findAllById(plantIds)) {
            plantNameById.put(plant.getId(), plant.getNickname());
        }
        return plantNameById;
    }

    private Optional<PlantDeviceAssignment> activeAssignmentOfPlant(String plantId) {
        return assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(plantId);
    }

    /**
     * device_uid 는 robot 과 iot_device 를 통틀어 <strong>활성 행끼리</strong> 유일해야 한다.
     * MQTT 토픽 세그먼트로 쓰이므로 활성이 겹치면 어느 장치의 측정값인지 가릴 수 없다.
     * 해제된 행은 이력이라 검사에서 빠진다 — 그래서 넘겨받은 기기를 같은 코드로 등록할 수 있다.
     */
    private void requireUnusedDeviceUid(String deviceUid) {
        if (robotRepository.existsByDeviceUidAndReleasedAtIsNull(deviceUid)
                || iotDeviceRepository.existsByDeviceUidAndReleasedAtIsNull(deviceUid)) {
            throw new BusinessException(ErrorCode.DEVICE_UID_ALREADY_REGISTERED);
        }
    }

    private AppUser findUser(String userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /** 타인 로봇은 존재 여부를 노출하지 않도록 403 이 아니라 404 다. */
    private Robot findOwnedRobot(String userId, String robotId) {
        return robotRepository.findByIdAndUserIdAndReleasedAtIsNull(robotId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));
    }

    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
