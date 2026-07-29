package com.potner.mqtt.application;

import com.potner.device.application.BatteryUpdateResult;
import com.potner.device.application.RobotBatteryService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatteryMessageProcessorTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final String JETSON_TOPIC = "potner/device/jetson-01/status/battery";

    private RobotBatteryService robotBatteryService;
    private BatteryMessageProcessor processor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        robotBatteryService = mock(RobotBatteryService.class);
        processor = new BatteryMessageProcessor(
                objectMapper,
                validator,
                new BatteryTopicParser(),
                robotBatteryService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void batteryPercentReachesTheService() {
        when(robotBatteryService.recordBattery(anyString(), anyInt()))
                .thenReturn(BatteryUpdateResult.UPDATED);

        processor.process(JETSON_TOPIC, json("jetson-01", "78", "2026-07-29T07:59:00Z"));

        verify(robotBatteryService).recordBattery("jetson-01", 78);
    }

    @Test
    void bothEndsOfTheAllowedRangeAreAccepted() {
        when(robotBatteryService.recordBattery(anyString(), anyInt()))
                .thenReturn(BatteryUpdateResult.UPDATED);

        processor.process(JETSON_TOPIC, json("jetson-01", "0", "2026-07-29T07:59:00Z"));
        processor.process(JETSON_TOPIC, json("jetson-01", "100", "2026-07-29T07:59:00Z"));

        verify(robotBatteryService).recordBattery("jetson-01", 0);
        verify(robotBatteryService).recordBattery("jetson-01", 100);
    }

    @Test
    void outOfRangeIsDroppedRatherThanClamped() {
        // 0 이나 100 으로 깎으면 고장 난 센서가 정상값을 보내는 것처럼 보여 원인을 찾을 수 없다.
        // DB CHECK 제약도 같은 범위라 여기서 막지 않으면 저장 단계에서 예외가 난다.
        processor.process(JETSON_TOPIC, json("jetson-01", "-1", "2026-07-29T07:59:00Z"));
        processor.process(JETSON_TOPIC, json("jetson-01", "101", "2026-07-29T07:59:00Z"));
        processor.process(JETSON_TOPIC, json("jetson-01", "255", "2026-07-29T07:59:00Z"));

        verify(robotBatteryService, never()).recordBattery(anyString(), anyInt());
    }

    @Test
    void fractionalPercentIsTruncatedRatherThanDropped() {
        // Jackson 이 실수를 int 필드에 넣을 때 내린다. 거부하도록 바꿀 수도 있지만 배터리
        // 퍼센트에서 소수점을 버리는 것은 잃는 것이 없고, 거부하면 쓸 수 있는 값을 버린다.
        // 규격은 정수를 요구하되 실수가 와도 동작한다.
        when(robotBatteryService.recordBattery(anyString(), anyInt()))
                .thenReturn(BatteryUpdateResult.UPDATED);

        processor.process(JETSON_TOPIC, json("jetson-01", "78.9", "2026-07-29T07:59:00Z"));

        verify(robotBatteryService).recordBattery("jetson-01", 78);
    }

    @Test
    void outOfRangeFractionalIsStillDropped() {
        // 내림이 범위 검사를 우회하지 않아야 한다. 100.6 은 내려서 100 이 되지만 101.2 는
        // 101 이 되어 CHECK 제약을 위반한다.
        when(robotBatteryService.recordBattery(anyString(), anyInt()))
                .thenReturn(BatteryUpdateResult.UPDATED);

        processor.process(JETSON_TOPIC, json("jetson-01", "101.2", "2026-07-29T07:59:00Z"));

        verify(robotBatteryService, never()).recordBattery(anyString(), anyInt());
    }

    @Test
    void payloadClaimingAnotherDeviceIsRejected() {
        processor.process(JETSON_TOPIC, json("jetson-99", "78", "2026-07-29T07:59:00Z"));

        verify(robotBatteryService, never()).recordBattery(anyString(), anyInt());
    }

    @Test
    void futureTimestampsBrokenPayloadsAndWrongTopicsAreRejected() {
        processor.process(JETSON_TOPIC, json("jetson-01", "78", "2026-07-29T09:00:00Z"));
        processor.process(JETSON_TOPIC, "not json");
        processor.process(JETSON_TOPIC, "{\"deviceId\":\"jetson-01\",\"batteryPercent\":78}");
        processor.process("potner/device/jetson-01/status/state",
                json("jetson-01", "78", "2026-07-29T07:59:00Z"));

        verify(robotBatteryService, never()).recordBattery(anyString(), anyInt());
    }

    @Test
    void rejectedDeviceTypeDoesNotBreakTheSubscriber() {
        // 라즈베리가 규격을 어기고 보고를 시작한 경우다. 예외가 올라가면 MQTT 콜백 스레드가
        // 깨져 센서 수집까지 멈춘다.
        when(robotBatteryService.recordBattery(anyString(), anyInt()))
                .thenReturn(BatteryUpdateResult.UNEXPECTED_DEVICE_TYPE);

        assertThatCode(() -> processor.process(
                JETSON_TOPIC,
                json("jetson-01", "78", "2026-07-29T07:59:00Z")))
                .doesNotThrowAnyException();
    }

    private String json(String deviceId, String batteryPercent, String measuredAt) {
        return """
                {"messageId":"3f2b1c8e-0000-0000-0000-00000000000a",
                 "deviceId":"%s","batteryPercent":%s,"measuredAt":"%s"}
                """.formatted(deviceId, batteryPercent, measuredAt);
    }
}
