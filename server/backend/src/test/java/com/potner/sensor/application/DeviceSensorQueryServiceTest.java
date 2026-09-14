package com.potner.sensor.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.DeviceUploadToken;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.sensor.dto.CurrentSensorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceSensorQueryServiceTest {

    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String TOKEN = "url-safe-raw-device-token";

    @Mock
    private RobotRepository robotRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private SensorQueryService sensorQueryService;

    /** 순수 해시 함수라 실물을 쓴다 — 저장소에 원문이 아니라 해시가 넘어가는지 그대로 검증된다. */
    private final DeviceUploadToken uploadTokenIssuer = new DeviceUploadToken();

    private DeviceSensorQueryService deviceSensorQueryService;

    @BeforeEach
    void setUp() {
        deviceSensorQueryService = new DeviceSensorQueryService(
                robotRepository,
                assignmentRepository,
                uploadTokenIssuer,
                sensorQueryService
        );
    }

    @Test
    void nullTokenIsRejectedWithoutTouchingRepositories() {
        assertThatThrownBy(() -> deviceSensorQueryService.getCurrentSensors(null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_DEVICE_TOKEN);

        verifyNoInteractions(robotRepository, assignmentRepository, sensorQueryService);
    }

    @Test
    void blankTokenIsRejectedWithoutTouchingRepositories() {
        assertThatThrownBy(() -> deviceSensorQueryService.getCurrentSensors("   "))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_DEVICE_TOKEN);

        verifyNoInteractions(robotRepository, assignmentRepository, sensorQueryService);
    }

    @Test
    void tokenIsHashedBeforeLookupAndUnknownTokenIsRejected() {
        when(robotRepository.findByUploadTokenHashAndReleasedAtIsNull(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceSensorQueryService.getCurrentSensors(TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_DEVICE_TOKEN);

        // 원문이 그대로 저장소로 흘러가면 안 된다 — 해시 대조가 이 인증의 전제다.
        ArgumentCaptor<String> lookupKey = ArgumentCaptor.forClass(String.class);
        verify(robotRepository).findByUploadTokenHashAndReleasedAtIsNull(lookupKey.capture());
        assertThat(lookupKey.getValue())
                .isEqualTo(uploadTokenIssuer.hash(TOKEN))
                .isNotEqualTo(TOKEN);
    }

    @Test
    void robotWithoutActiveAssignmentIsRejected() {
        Robot robot = mock(Robot.class);
        when(robot.getId()).thenReturn(ROBOT_ID);
        when(robotRepository.findByUploadTokenHashAndReleasedAtIsNull(uploadTokenIssuer.hash(TOKEN)))
                .thenReturn(Optional.of(robot));
        when(assignmentRepository.findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceSensorQueryService.getCurrentSensors(TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND);

        verifyNoInteractions(sensorQueryService);
    }

    @Test
    void activeAssignmentResolvesPlantAndDelegatesQuery() {
        Robot robot = mock(Robot.class);
        when(robot.getId()).thenReturn(ROBOT_ID);
        when(robotRepository.findByUploadTokenHashAndReleasedAtIsNull(uploadTokenIssuer.hash(TOKEN)))
                .thenReturn(Optional.of(robot));
        PlantDeviceAssignment assignment = PlantDeviceAssignment.assign(
                PLANT_ID, ROBOT_ID, LocalDateTime.of(2026, 8, 4, 12, 0));
        when(assignmentRepository.findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.of(assignment));
        CurrentSensorResponse expected = new CurrentSensorResponse(PLANT_ID, List.of());
        when(sensorQueryService.currentSensorsForVerifiedPlant(PLANT_ID)).thenReturn(expected);

        CurrentSensorResponse response = deviceSensorQueryService.getCurrentSensors(TOKEN);

        assertThat(response).isSameAs(expected);
    }
}
