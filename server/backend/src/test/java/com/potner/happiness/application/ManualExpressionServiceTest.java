package com.potner.happiness.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.PlantExpression;
import com.potner.happiness.dto.ExpressionCommandPayload;
import com.potner.happiness.dto.PublishExpressionRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualExpressionServiceTest {

    private static final String USER_ID = "user-1";
    private static final String PLANT_ID = "plant-1";
    private static final String ROBOT_ID = "robot-1";

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    @Mock
    private RobotCommandPublisher commandPublisher;

    private ManualExpressionService service;

    @BeforeEach
    void setUp() {
        service = new ManualExpressionService(
                plantRepository,
                assignmentRepository,
                iotDeviceRepository,
                commandPublisher,
                new HappinessProperties(
                        true,
                        new BigDecimal("0.5"),
                        30,
                        IotDeviceType.JETSON_ORIN,
                        5,
                        5
                )
        );
    }

    @Test
    void publishesTheRequestedExpressionToTheDisplayDevice() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(
                device("raspberry-01", IotDeviceType.RASPBERRY_PI),
                device("jetson-01", IotDeviceType.JETSON_ORIN)
        );

        service.publish(
                USER_ID,
                PLANT_ID,
                new PublishExpressionRequest(PlantExpression.SAD, ExpressionReason.TEMPERATURE)
        );

        ArgumentCaptor<RobotCommand> commands = ArgumentCaptor.forClass(RobotCommand.class);
        // 디스플레이가 달린 장치만 받는다. 라즈베리파이로 가면 아무 일도 일어나지 않는다.
        verify(commandPublisher).publish(eq("jetson-01"), commands.capture());
        assertThat(commands.getValue().name()).isEqualTo("expression");
        assertThat((ExpressionCommandPayload) commands.getValue().payload()).isEqualTo(
                new ExpressionCommandPayload(
                        PLANT_ID,
                        PlantExpression.SAD,
                        ExpressionReason.TEMPERATURE
                )
        );
    }

    @Test
    void missingReasonFallsBackToNone() {
        givenOwnedPlant();
        givenActiveAssignment();
        givenDevices(device("jetson-01", IotDeviceType.JETSON_ORIN));

        service.publish(
                USER_ID,
                PLANT_ID,
                new PublishExpressionRequest(PlantExpression.HAPPY, null)
        );

        ArgumentCaptor<RobotCommand> commands = ArgumentCaptor.forClass(RobotCommand.class);
        verify(commandPublisher).publish(eq("jetson-01"), commands.capture());
        assertThat(((ExpressionCommandPayload) commands.getValue().payload()).reason())
                .isEqualTo(ExpressionReason.NONE);
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(
                USER_ID,
                PLANT_ID,
                new PublishExpressionRequest(PlantExpression.HAPPY, null)
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

        assertThatThrownBy(() -> service.publish(
                USER_ID,
                PLANT_ID,
                new PublishExpressionRequest(PlantExpression.HAPPY, null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND);
        verify(commandPublisher, never()).publish(anyString(), any());
    }

    @Test
    void robotWithoutADisplayDeviceIsRejected() {
        givenOwnedPlant();
        givenActiveAssignment();
        // 라즈베리파이만 등록된 로봇이다. 표정을 그릴 화면이 없다.
        givenDevices(device("raspberry-01", IotDeviceType.RASPBERRY_PI));

        assertThatThrownBy(() -> service.publish(
                USER_ID,
                PLANT_ID,
                new PublishExpressionRequest(PlantExpression.HAPPY, null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DEVICE_NOT_FOUND);
        verify(commandPublisher, never()).publish(anyString(), any());
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
