package com.potner.device.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceConnectionStatus;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.device.dto.PlantDeviceListResponse;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceQueryServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final LocalDateTime ASSIGNED_AT = LocalDateTime.of(2026, 7, 20, 0, 0);
    private static final LocalDateTime OLDER_SEEN = LocalDateTime.of(2026, 7, 26, 0, 30);
    private static final LocalDateTime LATEST_SEEN = LocalDateTime.of(2026, 7, 26, 0, 40);

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private RobotRepository robotRepository;

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    private DeviceQueryService deviceQueryService;

    @BeforeEach
    void setUp() {
        deviceQueryService = new DeviceQueryService(
                plantRepository,
                assignmentRepository,
                robotRepository,
                iotDeviceRepository
        );
    }

    @Test
    void plantOwnedByAnotherUserIsRejected() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceQueryService.getPlantDevices(USER_ID, PLANT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PLANT_NOT_FOUND);
    }

    @Test
    void plantWithoutAssignedRobotReturnsEmptyPayloadInsteadOfError() {
        givenOwnedPlant();
        when(assignmentRepository.findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.empty());

        PlantDeviceListResponse response = deviceQueryService.getPlantDevices(USER_ID, PLANT_ID);

        assertThat(response.plantId()).isEqualTo(PLANT_ID);
        assertThat(response.robot()).isNull();
        assertThat(response.devices()).isEmpty();
    }

    @Test
    void robotIsOnlineWhenAnyDeviceIsOnline() {
        givenOwnedPlantWithRobot();
        givenDevices(
                device(IotDeviceConnectionStatus.OFFLINE, OLDER_SEEN),
                device(IotDeviceConnectionStatus.ONLINE, LATEST_SEEN)
        );

        PlantDeviceListResponse response = deviceQueryService.getPlantDevices(USER_ID, PLANT_ID);

        assertThat(response.robot().connectionStatus()).isEqualTo(IotDeviceConnectionStatus.ONLINE);
        // 장치들이 마지막으로 응답한 시각 중 가장 최근값을 쓴다.
        assertThat(response.robot().lastSeenAt()).isEqualTo(LATEST_SEEN);
        assertThat(response.robot().assignedAt()).isEqualTo(ASSIGNED_AT);
        assertThat(response.devices()).hasSize(2);
    }

    @Test
    void robotIsOfflineOnlyWhenEveryDeviceIsOffline() {
        givenOwnedPlantWithRobot();
        givenDevices(
                device(IotDeviceConnectionStatus.OFFLINE, OLDER_SEEN),
                device(IotDeviceConnectionStatus.OFFLINE, LATEST_SEEN)
        );

        PlantDeviceListResponse response = deviceQueryService.getPlantDevices(USER_ID, PLANT_ID);

        assertThat(response.robot().connectionStatus()).isEqualTo(IotDeviceConnectionStatus.OFFLINE);
        assertThat(response.robot().lastSeenAt()).isEqualTo(LATEST_SEEN);
    }

    @Test
    void robotWithoutAnyHeartbeatHasNoLastSeenAt() {
        givenOwnedPlantWithRobot();
        givenDevices(
                device(IotDeviceConnectionStatus.OFFLINE, null),
                device(IotDeviceConnectionStatus.OFFLINE, null)
        );

        PlantDeviceListResponse response = deviceQueryService.getPlantDevices(USER_ID, PLANT_ID);

        assertThat(response.robot().connectionStatus()).isEqualTo(IotDeviceConnectionStatus.OFFLINE);
        assertThat(response.robot().lastSeenAt()).isNull();
    }

    private void givenOwnedPlant() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
    }

    private void givenOwnedPlantWithRobot() {
        givenOwnedPlant();
        PlantDeviceAssignment assignment = mock(PlantDeviceAssignment.class);
        when(assignment.getRobotId()).thenReturn(ROBOT_ID);
        when(assignment.getAssignedAt()).thenReturn(ASSIGNED_AT);
        when(assignmentRepository.findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.of(assignment));

        Robot robot = mock(Robot.class);
        when(robot.getId()).thenReturn(ROBOT_ID);
        when(robotRepository.findById(ROBOT_ID)).thenReturn(Optional.of(robot));
    }

    private void givenDevices(IotDevice... devices) {
        when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(ROBOT_ID))
                .thenReturn(List.of(devices));
    }

    private IotDevice device(IotDeviceConnectionStatus status, LocalDateTime lastSeenAt) {
        IotDevice device = mock(IotDevice.class);
        when(device.getConnectionStatus()).thenReturn(status);
        when(device.getLastSeenAt()).thenReturn(lastSeenAt);
        return device;
    }
}
