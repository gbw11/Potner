package com.potner.mqtt.application;

import com.potner.device.application.RobotStateService;
import com.potner.device.application.RobotStateUpdateResult;
import com.potner.mqtt.dto.RobotStateMessage;
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
 * 로봇 상태 보고를 받아 저장한다.
 *
 * <p>센서·heartbeat 처리와 같은 순서를 지킨다. 특히 <strong>토픽과 페이로드의 장치 식별자를
 * 대조</strong>하는 단계가 빠지면 안 된다. ACL 은 장치가 어느 토픽에 쓸 수 있는지만 제한하고
 * 페이로드 내용은 검사하지 못한다. 대조가 없으면 한 로봇이 다른 로봇의 상태를 위조할 수 있고,
 * 급수 작업에서는 그것이 대기 장소에 있는 로봇을 "스테이션 도착"으로 속여 물을 쏟는 사고가 된다.
 */
@Component
public class RobotStateMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(RobotStateMessageProcessor.class);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(10);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final RobotStateTopicParser topicParser;
    private final RobotStateService robotStateService;
    private final Clock clock;

    public RobotStateMessageProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            RobotStateTopicParser topicParser,
            RobotStateService robotStateService,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.topicParser = topicParser;
        this.robotStateService = robotStateService;
        this.clock = clock;
    }

    public void process(String topic, String rawPayload) {
        Optional<String> topicDeviceId = topicParser.extractDeviceId(topic);
        if (topicDeviceId.isEmpty()) {
            log.warn("Invalid MQTT robot state topic: topic={}", topic);
            return;
        }

        RobotStateMessage message;
        try {
            message = objectMapper.readValue(rawPayload, RobotStateMessage.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            // 서버가 모르는 state 값이 오면 역직렬화가 여기서 실패한다. 하드웨어가 상태를
            // 추가했다는 뜻이므로 페이로드를 남겨야 원인을 알 수 있다.
            log.warn("Invalid MQTT robot state JSON: payload={}", summarize(rawPayload));
            return;
        }

        Set<ConstraintViolation<RobotStateMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String invalidFields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining(","));
            log.warn(
                    "Invalid MQTT robot state values: fields={}, payload={}",
                    invalidFields,
                    summarize(rawPayload)
            );
            return;
        }

        if (!message.changedAt().toInstant().isBefore(clock.instant().plus(MAX_FUTURE_SKEW))) {
            log.warn(
                    "Invalid MQTT robot state changedAt: messageId={}, deviceId={}, changedAt={}",
                    message.messageId(),
                    message.deviceId(),
                    message.changedAt()
            );
            return;
        }

        if (!topicDeviceId.get().equals(message.deviceId())) {
            log.warn(
                    "MQTT robot state deviceId mismatch: topicDeviceId={}, payloadDeviceId={}",
                    topicDeviceId.get(),
                    message.deviceId()
            );
            return;
        }

        RobotStateUpdateResult result = robotStateService.recordState(
                message.deviceId(),
                message.state()
        );
        switch (result) {
            case CHANGED -> log.info(
                    "Robot state changed: deviceId={}, state={}, messageId={}",
                    message.deviceId(),
                    message.state(),
                    message.messageId()
            );
            // 같은 상태가 계속 오는 것이 정상이다. info 로 남기면 로그가 이것만으로 찬다.
            case UNCHANGED -> log.debug(
                    "Robot state unchanged: deviceId={}, state={}",
                    message.deviceId(),
                    message.state()
            );
            case DEVICE_NOT_FOUND -> log.warn(
                    "MQTT robot state ignored because IoT device was not found: "
                            + "deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            case ROBOT_NOT_FOUND -> log.warn(
                    "MQTT robot state ignored because robot was not found: "
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
