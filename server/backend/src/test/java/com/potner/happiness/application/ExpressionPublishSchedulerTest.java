package com.potner.happiness.application;

import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.PlantExpression;
import com.potner.happiness.dto.ExpressionCommandPayload;
import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import com.potner.plant.domain.PlantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpressionPublishSchedulerTest {

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private HappinessService happinessService;

    @Mock
    private RobotCommandPublisher commandPublisher;

    private ExpressionPublishScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ExpressionPublishScheduler(
                assignmentRepository,
                happinessService,
                commandPublisher,
                new HappinessProperties(true, new BigDecimal("0.5"), 30, IotDeviceType.JETSON_ORIN, 5, 5)
        );
    }

    @Test
    void expressionGoesToTheDisplayDeviceOfEveryAssignedPlant() {
        givenTargets(target("plant-1", "jetson-01"), target("plant-2", "jetson-02"));
        when(happinessService.judge("plant-1")).thenReturn(
                PlantExpressionState.of(PlantExpression.HAPPY, ExpressionReason.SUNLIGHT));
        when(happinessService.judge("plant-2")).thenReturn(
                PlantExpressionState.of(PlantExpression.SAD, ExpressionReason.TEMPERATURE));

        assertThat(scheduler.publishOnce()).isEqualTo(2);

        ArgumentCaptor<RobotCommand> commands = ArgumentCaptor.forClass(RobotCommand.class);
        verify(commandPublisher).publish(eq("jetson-01"), commands.capture());
        assertThat(commands.getValue().name()).isEqualTo("expression");
        assertThat((ExpressionCommandPayload) commands.getValue().payload())
                .isEqualTo(new ExpressionCommandPayload(
                        "plant-1", PlantExpression.HAPPY, ExpressionReason.SUNLIGHT));
        verify(commandPublisher).publish(eq("jetson-02"), any());
    }

    @Test
    void onlyTheConfiguredDeviceTypeIsAsked() {
        // 디스플레이가 젯슨에 달려 있다. 라즈베리에 표정을 보내면 아무도 안 그린다.
        givenTargets();

        scheduler.publishOnce();

        verify(assignmentRepository)
                .findActiveAssignedDevices(IotDeviceType.JETSON_ORIN, PlantStatus.DELETED);
    }

    @Test
    void oneBrokenPlantDoesNotStopTheOthers() {
        givenTargets(target("plant-broken", "jetson-01"), target("plant-ok", "jetson-02"));
        when(happinessService.judge("plant-broken")).thenThrow(new IllegalStateException("boom"));
        when(happinessService.judge("plant-ok")).thenReturn(
                PlantExpressionState.of(PlantExpression.NEUTRAL, ExpressionReason.NONE));

        assertThat(scheduler.publishOnce()).isEqualTo(1);
        verify(commandPublisher).publish(eq("jetson-02"), any());
    }

    @Test
    void publishingCanBeTurnedOffWithoutTouchingTheBroker() {
        ExpressionPublishScheduler disabled = new ExpressionPublishScheduler(
                assignmentRepository,
                happinessService,
                commandPublisher,
                new HappinessProperties(false, new BigDecimal("0.5"), 30, IotDeviceType.JETSON_ORIN, 5, 5)
        );

        disabled.publishExpressions();

        verify(assignmentRepository, never()).findActiveAssignedDevices(any(), any());
        verify(commandPublisher, never()).publish(any(), any());
    }

    @Test
    void noAssignedRobotMeansNothingToSend() {
        givenTargets();

        assertThatCode(() -> assertThat(scheduler.publishOnce()).isZero())
                .doesNotThrowAnyException();
        verify(commandPublisher, never()).publish(any(), any());
    }

    private void givenTargets(AssignedDeviceView... targets) {
        when(assignmentRepository.findActiveAssignedDevices(
                IotDeviceType.JETSON_ORIN, PlantStatus.DELETED))
                .thenReturn(List.of(targets));
    }

    private AssignedDeviceView target(String plantId, String deviceUid) {
        AssignedDeviceView view = mock(AssignedDeviceView.class);
        org.mockito.Mockito.lenient().when(view.getPlantId()).thenReturn(plantId);
        org.mockito.Mockito.lenient().when(view.getDeviceUid()).thenReturn(deviceUid);
        return view;
    }
}
