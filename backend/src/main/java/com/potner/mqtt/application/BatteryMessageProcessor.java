package com.potner.mqtt.application;

import com.potner.device.application.BatteryUpdateResult;
import com.potner.device.application.RobotBatteryService;
import com.potner.mqtt.dto.BatteryMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 배터리 잔량 보고를 받아 저장한다.
 *
 * <p>센서·heartbeat·로봇 상태 처리와 같은 순서를 지킨다. 토픽과 페이로드의 장치 식별자를
 * 대조하는 단계가 빠지면 안 된다. ACL 은 장치가 어느 토픽에 쓸 수 있는지만 제한하고 페이로드
 * 내용은 검사하지 못한다.
 */
@Component
public class BatteryMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatteryMessageProcessor.class);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(10);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final BatteryTopicParser topicParser;
    private final RobotBatteryService robotBatteryService;
    private final Clock clock;

    public BatteryMessageProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            BatteryTopicParser topicParser,
            RobotBatteryService robotBatteryService,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.topicParser = topicParser;
        this.robotBatteryService = robotBatteryService;
        this.clock = clock;
    }

    public void process(String topic, String rawPayload) {
        Optional<String> topicDeviceId = topicParser.extractDeviceId(topic);
        if (topicDeviceId.isEmpty()) {
            log.warn("Invalid MQTT battery topic: topic={}", topic);
            return;
        }

        BatteryMessage message;
        try {
            message = objectMapper.readValue(rawPayload, BatteryMessage.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Invalid MQTT battery JSON: payload={}", summarize(rawPayload));
            return;
        }

        Set<ConstraintViolation<BatteryMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            // 범위를 벗어난 잔량이 여기서 걸린다. 0 이나 100 으로 깎지 않는다. 깎으면 고장 난
            // 센서가 정상값을 보내는 것처럼 보여 원인을 찾을 수 없다.
            String invalidFields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining(","));
            log.warn(
                    "Invalid MQTT battery values: fields={}, payload={}",
                    invalidFields,
                    summarize(rawPayload)
            );
            return;
        }

        if (!message.measuredAt().toInstant().isBefore(clock.instant().plus(MAX_FUTURE_SKEW))) {
            log.warn(
                    "Invalid MQTT battery measuredAt: messageId={}, deviceId={}, measuredAt={}",
                    message.messageId(),
                    message.deviceId(),
                    message.measuredAt()
            );
            return;
        }

        if (!topicDeviceId.get().equals(message.deviceId())) {
            log.warn(
                    "MQTT battery deviceId mismatch: topicDeviceId={}, payloadDeviceId={}",
                    topicDeviceId.get(),
                    message.deviceId()
            );
            return;
        }

        BatteryUpdateResult result = robotBatteryService.recordBattery(
                message.deviceId(),
                message.batteryPercent()
        );
        switch (result) {
            case UPDATED -> log.debug(
                    "Robot battery recorded: deviceId={}, batteryPercent={}",
                    message.deviceId(),
                    message.batteryPercent()
            );
            case DEVICE_NOT_FOUND -> log.warn(
                    "MQTT battery ignored because IoT device was not found: "
                            + "deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            case ROBOT_NOT_FOUND -> log.warn(
                    "MQTT battery ignored because robot was not found: deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            // 규격을 벗어난 장치가 보고를 시작했다. 두 장치가 보내면 서로 덮어써 값이 흔들리므로
            // 조용히 받지 않고 드러낸다.
            case UNEXPECTED_DEVICE_TYPE -> log.warn(
                    "MQTT battery ignored because the device type is not the configured reporter: "
                            + "deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
        }
    }

    private String summarize(String payload) {
        String singleLine = payload.replace("\r", "\\r").replace("\n", "\\n");
        if (singleLine.length() <= MAX_LOG_PAYLOAD_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...";
    }
}
