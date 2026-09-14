package com.potner.sensor.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.DeviceUploadToken;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.sensor.dto.CurrentSensorResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장치가 담당 식물의 센서 최신값을 조회하는 경로다.
 *
 * <p>장치는 사용자 JWT 를 가질 수 없으므로 업로드 토큰(사진 업로드와 같은 토큰)으로
 * 인증하고, 어느 식물인지는 요청이 아니라 <strong>로봇의 활성 배정에서 서버가 정한다</strong>.
 * 장치가 plantId 를 지정할 수 있으면 토큰 하나로 남의 식물 데이터를 읽을 수 있다.
 *
 * <p>토큰 검증은 {@code PhotoService.findRobotByToken} 과 같은 규칙의 의도적 중복이다 —
 * 이 레포는 검증을 공용 컴포넌트로 빼는 대신 서비스마다 자기 손으로 하는 관례를 따른다.
 */
@Service
@Transactional(readOnly = true)
public class DeviceSensorQueryService {

    private final RobotRepository robotRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final DeviceUploadToken uploadTokenIssuer;
    private final SensorQueryService sensorQueryService;

    public DeviceSensorQueryService(
            RobotRepository robotRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            DeviceUploadToken uploadTokenIssuer,
            SensorQueryService sensorQueryService
    ) {
        this.robotRepository = robotRepository;
        this.assignmentRepository = assignmentRepository;
        this.uploadTokenIssuer = uploadTokenIssuer;
        this.sensorQueryService = sensorQueryService;
    }

    public CurrentSensorResponse getCurrentSensors(String uploadToken) {
        Robot robot = findRobotByToken(uploadToken);
        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robot.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND));
        return sensorQueryService.currentSensorsForVerifiedPlant(assignment.getPlantId());
    }

    /** 토큰이 비었거나 맞는 로봇이 없으면 401 이다. 어느 쪽인지는 구분해 알려주지 않는다. */
    private Robot findRobotByToken(String uploadToken) {
        if (uploadToken == null || uploadToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN);
        }
        return robotRepository.findByUploadTokenHashAndReleasedAtIsNull(uploadTokenIssuer.hash(uploadToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN));
    }
}
