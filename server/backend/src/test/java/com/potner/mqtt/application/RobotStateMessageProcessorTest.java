package com.potner.mqtt.application;

import com.potner.device.application.RobotStateService;
import com.potner.device.application.RobotStateUpdateResult;
import com.potner.device.domain.RobotState;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RobotStateMessageProcessorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T05:30:00Z");
    private static final String JETSON_TOPIC = "potner/device/jetson-01/status/state";

    private RobotStateService robotStateService;
    private RobotStateMessageProcessor processor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        robotStateService = mock(RobotStateService.class);
        processor = new RobotStateMessageProcessor(
                objectMapper,
                validator,
                new RobotStateTopicParser(),
                robotStateService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void everyStateTheHardwareSendsIsAccepted() {
        when(robotStateService.recordState(anyString(), any()))
                .thenReturn(RobotStateUpdateResult.CHANGED);

        for (RobotState state : RobotState.values()) {
            processor.process(JETSON_TOPIC, json("jetson-01", state.name(), "2026-07-23T05:29:00Z"));
            verify(robotStateService).recordState("jetson-01", state);
        }
    }

    @Test
    void repeatedSameStateIsForwardedEveryTimeAndTheServiceDecides() {
        // 젯슨이 이동하는 동안 NAVIGATING 을 계속 보낸다. 처리기가 중복을 판단하지 않고
        // 서비스가 상태 비교로 결정한다. 그래야 상태 비교가 한 곳에만 있다.
        when(robotStateService.recordState(anyString(), any()))
                .thenReturn(RobotStateUpdateResult.CHANGED, RobotStateUpdateResult.UNCHANGED);

        processor.process(JETSON_TOPIC, json("jetson-01", "NAVIGATING", "2026-07-23T05:29:00Z"));
        processor.process(JETSON_TOPIC, json("jetson-01", "NAVIGATING", "2026-07-23T05:29:30Z"));

        verify(robotStateService, times(2)).recordState("jetson-01", RobotState.NAVIGATING);
    }

    @Test
    void payloadClaimingAnotherDeviceIsRejected() {
        // ACL 은 장치가 어느 토픽에 쓸 수 있는지만 제한하고 페이로드는 검사하지 못한다.
        // 이 대조가 없으면 한 로봇이 다른 로봇을 '스테이션 도착' 으로 속여 물을 쏟게 만든다.
        processor.process(JETSON_TOPIC, json("jetson-99", "IDLE", "2026-07-23T05:29:00Z"));

        verify(robotStateService, never()).recordState(anyString(), any());
    }

    @Test
    void unknownStateIsRejectedInsteadOfGuessing() {
        // 하드웨어가 상태를 추가하면 여기서 걸린다. 임의로 IDLE 로 떨어뜨리면 급수 시퀀스가
        // 엉뚱한 판단을 한다.
        processor.process(JETSON_TOPIC, json("jetson-01", "CHARGING", "2026-07-23T05:29:00Z"));

        verify(robotStateService, never()).recordState(anyString(), any());
    }

    @Test
    void futureTimestampsAndBrokenPayloadsAreRejected() {
        processor.process(JETSON_TOPIC, json("jetson-01", "IDLE", "2026-07-23T06:30:00Z"));
        processor.process(JETSON_TOPIC, "not json");
        processor.process(JETSON_TOPIC, "{\"deviceId\":\"jetson-01\",\"state\":\"IDLE\"}");
        processor.process("potner/device/jetson-01/status/heartbeat",
                json("jetson-01", "IDLE", "2026-07-23T05:29:00Z"));

        verify(robotStateService, never()).recordState(anyString(), any());
    }

    @Test
    void unknownDeviceDoesNotBreakTheSubscriber() {
        // 예외가 올라가면 MQTT 콜백 스레드가 깨져 센서 수집까지 멈춘다.
        when(robotStateService.recordState(anyString(), any()))
                .thenReturn(RobotStateUpdateResult.DEVICE_NOT_FOUND);

        assertThatCode(() -> processor.process(
                JETSON_TOPIC,
                json("jetson-01", "IDLE", "2026-07-23T05:29:00Z")))
                .doesNotThrowAnyException();
    }

    private String json(String deviceId, String state, String changedAt) {
        return """
                {"messageId":"1a2b3c4d-0000-0000-0000-00000000000a",
                 "deviceId":"%s","state":"%s","changedAt":"%s"}
                """.formatted(deviceId, state, changedAt);
    }
}
