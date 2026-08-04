package com.potner.command.application;

import com.potner.command.config.DriveProperties;
import com.potner.command.domain.DriveDirection;
import com.potner.command.dto.DriveRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualDriveServiceTest {

    private static final String USER_ID = "user-1";
    private static final String PLANT_ID = "plant-1";
    private static final String ROBOT_ID = "robot-1";

    private static final BigDecimal LINEAR_MPS = new BigDecimal("0.12");
    private static final BigDecimal ANGULAR_RPS = new BigDecimal("0.6");
    private static final int STEP_MILLIS = 600;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    @Mock
    private RobotCommandPublisher commandPublisher;

    private ManualDriveService service;

    @BeforeEach
    void setUp() {
        service = new ManualDriveService(
                plantRepository,
                assignmentRepository,
                iotDeviceRepository,
                commandPublisher,
                new DriveProperties(LINEAR_MPS, ANGULAR_RPS, STEP_MILLIS)
        );
    }

    @Test
    void publishesForwardDriveToTheWheelDevice() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(
                device("raspberry-01", IotDeviceType.RASPBERRY_PI),
                device("jetson-01", IotDeviceType.JETSON_ORIN)
        );

        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.FORWARD, null));

        // 바퀴는 젯슨에 달려 있다. 라즈베리파이로 가면 아무 일도 일어나지 않는다.
        DriveCommandPayload payload = capturePayload("jetson-01");
        assertThat(payload.direction()).isEqualTo("FORWARD");
        assertThat(payload.linearMps()).isEqualByComparingTo(LINEAR_MPS);
        assertThat(payload.angularRps()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(payload.durationMs()).isEqualTo(STEP_MILLIS);
        assertThat(payload.requestId()).isNotBlank();
    }

    @Test
    void backwardReversesTheLinearSign() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(device("jetson-01", IotDeviceType.JETSON_ORIN));

        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.BACKWARD, null));

        assertThat(capturePayload("jetson-01").linearMps())
                .isEqualByComparingTo(LINEAR_MPS.negate());
    }

    /**
     * ROS REP-103 에서 각속도 +z 는 반시계다. 부호가 뒤집히면 로봇이 반대로 돌아 사용자가
     * 화면에서 누른 방향과 어긋난다.
     */
    @Test
    void leftTurnsCounterClockwiseAndRightClockwise() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(device("jetson-01", IotDeviceType.JETSON_ORIN));

        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.LEFT, null));
        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.RIGHT, null));

        List<DriveCommandPayload> payloads = capturePayloads("jetson-01", 2);
        assertThat(payloads.get(0).angularRps()).isEqualByComparingTo(ANGULAR_RPS);
        assertThat(payloads.get(0).linearMps()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(payloads.get(1).angularRps()).isEqualByComparingTo(ANGULAR_RPS.negate());
    }

    @Test
    void explicitDurationOverridesTheServerDefault() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(device("jetson-01", IotDeviceType.JETSON_ORIN));

        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.FORWARD, 250));

        assertThat(capturePayload("jetson-01").durationMs()).isEqualTo(250);
    }

    /**
     * 정지는 손을 뗀 순간 세우는 것이 목적이므로 남은 시간을 기다리지 않는다. 요청이 시간을
     * 실어 보내도 따르지 않는다.
     */
    @Test
    void stopSendsZeroSpeedAndIgnoresTheRequestedDuration() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(device("jetson-01", IotDeviceType.JETSON_ORIN));

        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.STOP, 3000));

        DriveCommandPayload payload = capturePayload("jetson-01");
        assertThat(payload.linearMps()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(payload.angularRps()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(payload.durationMs()).isZero();
    }

    /**
     * 연달아 눌러도 막히지 않아야 한다. {@code device_command} 를 거치면 같은 종류의 명령이
     * 대기 중이라며 두 번째 누름부터 409 로 막히는데, 방향 버튼은 연타가 정상이다.
     */
    @Test
    void repeatedPressesAreAllPublished() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(device("jetson-01", IotDeviceType.JETSON_ORIN));

        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.FORWARD, null));
        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.FORWARD, null));
        service.drive(USER_ID, PLANT_ID, new DriveRequest(DriveDirection.FORWARD, null));

        List<DriveCommandPayload> payloads = capturePayloads("jetson-01", 3);
        // requestId 는 매번 달라야 한다. 로봇이 QoS 1 중복 수신을 이 값으로 걸러내므로 같으면
        // 두 번째 누름부터 무시된다.
        assertThat(payloads).extracting(DriveCommandPayload::requestId).doesNotHaveDuplicates();
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.drive(
                USER_ID,
                PLANT_ID,
                new DriveRequest(DriveDirection.FORWARD, null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PLANT_NOT_FOUND);
        verify(commandPublisher, never()).publish(anyString(), any());
    }

    @Test
    void plantWithoutAnAssignedRobotIsRejected() {
        givenOwnedPlant();
        when(assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.drive(
                USER_ID,
                PLANT_ID,
                new DriveRequest(DriveDirection.FORWARD, null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND);
        verify(commandPublisher, never()).publish(anyString(), any());
    }

    @Test
    void robotWithoutAWheelDeviceIsRejected() {
        givenOwnedPlant();
        givenActiveAssignment();
        // 라즈베리파이만 등록된 로봇이다. 굴릴 바퀴가 없다.
        givenDevices(device("raspberry-01", IotDeviceType.RASPBERRY_PI));

        assertThatThrownBy(() -> service.drive(
                USER_ID,
                PLANT_ID,
                new DriveRequest(DriveDirection.FORWARD, null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DEVICE_NOT_FOUND);
        verify(commandPublisher, never()).publish(anyString(), any());
    }

    private DriveCommandPayload capturePayload(String deviceUid) {
        return capturePayloads(deviceUid, 1).getFirst();
    }

    private List<DriveCommandPayload> capturePayloads(String deviceUid, int count) {
        ArgumentCaptor<RobotCommand> commands = ArgumentCaptor.forClass(RobotCommand.class);
        verify(commandPublisher, times(count)).publish(eq(deviceUid), commands.capture());
        return commands.getAllValues().stream()
                .peek(command -> assertThat(command.name()).isEqualTo("drive"))
                .map(command -> (DriveCommandPayload) command.payload())
                .toList();
    }

    private void givenOwnedPlant() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
    }

    private void givenActiveAssignment() {
        PlantDeviceAssignment assignment = mock(PlantDeviceAssignment.class);
        when(assignment.getRobotId()).thenReturn(ROBOT_ID);
        when(assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.of(assignment));
    }

    private void givenDevices(IotDevice... devices) {
        when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(ROBOT_ID))
                .thenReturn(List.of(devices));
    }

    private IotDevice device(String deviceUid, IotDeviceType type) {
        return IotDevice.register(mock(Robot.class), deviceUid, type);
    }
}
