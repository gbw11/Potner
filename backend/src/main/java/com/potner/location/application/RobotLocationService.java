package com.potner.location.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.RobotRepository;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.location.dto.RegisterRobotLocationRequest;
import com.potner.location.dto.RobotLocationListResponse;
import com.potner.location.dto.RobotLocationResponse;
import com.potner.location.dto.UpdateLocationPoseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위치의 등록·조회·좌표 입력이다.
 *
 * <p>등록과 좌표 입력이 분리된 이유: 하는 사람이 다르다. 등록(스테이션 코드)은 사용자가
 * 앱에서, 좌표는 지도를 만든 설치자가 RViz 에서 읽어 넣는다. 등록 시점에 좌표를 요구하면
 * 사용자가 알 수 없는 값을 채워야 하고, 좌표를 기다리면 온보딩이 막힌다.
 */
@Service
public class RobotLocationService {

    private final RobotLocationRepository locationRepository;
    private final RobotRepository robotRepository;

    public RobotLocationService(
            RobotLocationRepository locationRepository,
            RobotRepository robotRepository
    ) {
        this.locationRepository = locationRepository;
        this.robotRepository = robotRepository;
    }

    @Transactional
    public RobotLocationResponse register(
            String userId,
            String robotId,
            RegisterRobotLocationRequest request
    ) {
        requireOwnedRobot(userId, robotId);
        requireCodeMatchesType(request.type(), request.stationCode());

        // 로봇 하나에 종류별 위치는 하나다. 같은 종류를 다시 등록하면 좌표 수정인지 새 등록인지
        // 알 수 없으므로 충돌로 알리고, 좌표는 전용 경로로 고치게 한다.
        if (locationRepository.existsByRobotIdAndLocationType(robotId, request.type())) {
            throw new BusinessException(ErrorCode.ROBOT_LOCATION_ALREADY_REGISTERED);
        }
        if (request.stationCode() != null
                && locationRepository.existsByStationCode(request.stationCode())) {
            throw new BusinessException(ErrorCode.STATION_CODE_ALREADY_REGISTERED);
        }

        RobotLocation location = locationRepository.save(RobotLocation.register(
                robotId,
                request.type(),
                request.stationCode()
        ));
        return RobotLocationResponse.from(location);
    }

    @Transactional(readOnly = true)
    public RobotLocationListResponse getLocations(String userId, String robotId) {
        requireOwnedRobot(userId, robotId);
        return new RobotLocationListResponse(
                robotId,
                locationRepository.findAllByRobotIdOrderByLocationTypeAsc(robotId)
                        .stream()
                        .map(RobotLocationResponse::from)
                        .toList()
        );
    }

    @Transactional
    public RobotLocationResponse updatePose(
            String userId,
            String robotId,
            RobotLocationType type,
            UpdateLocationPoseRequest request
    ) {
        requireOwnedRobot(userId, robotId);
        RobotLocation location = locationRepository
                .findByRobotIdAndLocationType(robotId, type)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_LOCATION_NOT_FOUND));

        location.updatePose(request.x(), request.y(), request.yaw());
        return RobotLocationResponse.from(location);
    }

    /**
     * 코드는 물리 스테이션에만, 물리 스테이션에는 반드시 있어야 한다.
     *
     * <p>DTO 의 형식 검증과 별개다. 종류별 필수 여부는 형식이 아니라 업무 규칙이고,
     * 형식 애노테이션으로는 두 필드의 관계를 표현할 수 없다.
     */
    private void requireCodeMatchesType(RobotLocationType type, String stationCode) {
        boolean hasCode = stationCode != null && !stationCode.isBlank();
        if (type.isPhysicalStation() != hasCode) {
            throw new BusinessException(ErrorCode.INVALID_STATION_CODE);
        }
    }

    /** 타인 로봇은 존재 여부를 노출하지 않도록 403 이 아니라 404 다. */
    private void requireOwnedRobot(String userId, String robotId) {
        robotRepository.findByIdAndUserIdAndReleasedAtIsNull(robotId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));
    }
}
