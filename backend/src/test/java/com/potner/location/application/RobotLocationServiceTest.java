package com.potner.location.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.location.dto.RegisterRobotLocationRequest;
import com.potner.location.dto.RobotLocationResponse;
import com.potner.location.dto.UpdateLocationPoseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RobotLocationServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";

    @Mock
    private RobotLocationRepository locationRepository;

    @Mock
    private RobotRepository robotRepository;

    private RobotLocationService service;

    @BeforeEach
    void setUp() {
        service = new RobotLocationService(locationRepository, robotRepository);
        lenient().when(robotRepository.findByIdAndUserIdAndReleasedAtIsNull(ROBOT_ID, USER_ID))
                .thenReturn(Optional.of(mock(Robot.class)));
        lenient().when(locationRepository.save(any(RobotLocation.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void registersAWaterStationWithItsCode() {
        RobotLocationResponse response = service.register(
                USER_ID, ROBOT_ID,
                new RegisterRobotLocationRequest(RobotLocationType.WATER_STATION, "WS-1234"));

        assertThat(response.type()).isEqualTo(RobotLocationType.WATER_STATION);
        assertThat(response.stationCode()).isEqualTo("WS-1234");
        // 등록 시점에는 좌표가 없다. 코드 입력과 좌표 입력은 다른 사람이 다른 시점에 한다.
        assertThat(response.poseConfigured()).isFalse();
        assertThat(response.waterLow()).isFalse();
    }

    @Test
    void registersACoordinateOnlyLocationWithoutACode() {
        RobotLocationResponse response = service.register(
                USER_ID, ROBOT_ID,
                new RegisterRobotLocationRequest(RobotLocationType.SUNLIGHT, null));

        assertThat(response.stationCode()).isNull();
    }

    @Test
    void rejectsACodeOnACoordinateOnlyLocation() {
        // 그늘 자리에는 물리 장치가 없다. 코드가 오면 사용자가 화면을 잘못 이해한 것이다.
        assertThatThrownBy(() -> service.register(
                USER_ID, ROBOT_ID,
                new RegisterRobotLocationRequest(RobotLocationType.HOME, "WS-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_STATION_CODE);
        verify(locationRepository, never()).save(any());
    }

    @Test
    void rejectsAWaterStationWithoutACode() {
        assertThatThrownBy(() -> service.register(
                USER_ID, ROBOT_ID,
                new RegisterRobotLocationRequest(RobotLocationType.WATER_STATION, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_STATION_CODE);
    }

    @Test
    void rejectsADuplicateLocationType() {
        // 같은 종류 재등록이 좌표 수정인지 새 등록인지 알 수 없다. 좌표는 전용 경로로 고친다.
        when(locationRepository.existsByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.HOME))
                .thenReturn(true);

        assertThatThrownBy(() -> service.register(
                USER_ID, ROBOT_ID,
                new RegisterRobotLocationRequest(RobotLocationType.HOME, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROBOT_LOCATION_ALREADY_REGISTERED);
    }

    @Test
    void rejectsAStationCodeSomeoneElseAlreadyUses() {
        // 코드는 전역 UNIQUE 다. device_uid 와 같은 방침이다.
        when(locationRepository.existsByStationCode("WS-1234")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                USER_ID, ROBOT_ID,
                new RegisterRobotLocationRequest(RobotLocationType.WATER_STATION, "WS-1234")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.STATION_CODE_ALREADY_REGISTERED);
    }

    @Test
    void updatesThePoseOfARegisteredLocation() {
        RobotLocation location = RobotLocation.register(ROBOT_ID, RobotLocationType.SUNLIGHT, null);
        when(locationRepository.findByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.SUNLIGHT))
                .thenReturn(Optional.of(location));

        RobotLocationResponse response = service.updatePose(
                USER_ID, ROBOT_ID, RobotLocationType.SUNLIGHT,
                new UpdateLocationPoseRequest(
                        new BigDecimal("1.250"),
                        new BigDecimal("-0.480"),
                        new BigDecimal("1.5708")));

        assertThat(response.poseConfigured()).isTrue();
        assertThat(response.poseX()).isEqualByComparingTo("1.250");
        assertThat(response.poseY()).isEqualByComparingTo("-0.480");
        assertThat(response.poseYaw()).isEqualByComparingTo("1.5708");
    }

    @Test
    void rejectsAPoseForAnUnregisteredLocation() {
        when(locationRepository.findByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.GREETING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePose(
                USER_ID, ROBOT_ID, RobotLocationType.GREETING,
                new UpdateLocationPoseRequest(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROBOT_LOCATION_NOT_FOUND);
    }

    @Test
    void hidesOtherUsersRobots() {
        // 타인 로봇은 존재 여부를 노출하지 않도록 403 이 아니라 404 다.
        when(robotRepository.findByIdAndUserIdAndReleasedAtIsNull("other-robot", USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLocations(USER_ID, "other-robot"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND);
    }
}
