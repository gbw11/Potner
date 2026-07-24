package com.potner.mqtt.application;

import com.potner.device.application.HeartbeatUpdateResult;
import com.potner.device.application.IotDeviceHeartbeatService;
import com.potner.mqtt.dto.HeartbeatMessage;
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

@Component
public class HeartbeatMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMessageProcessor.class);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(10);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final HeartbeatTopicParser topicParser;
    private final IotDeviceHeartbeatService heartbeatService;
    private final Clock clock;

    public HeartbeatMessageProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            HeartbeatTopicParser topicParser,
            IotDeviceHeartbeatService heartbeatService,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.topicParser = topicParser;
        this.heartbeatService = heartbeatService;
        this.clock = clock;
    }

    public void process(String topic, String rawPayload) {
        Optional<String> topicDeviceId = topicParser.extractDeviceId(topic);
        if (topicDeviceId.isEmpty()) {
            log.warn("Invalid MQTT heartbeat topic: topic={}", topic);
            return;
        }

        HeartbeatMessage message;
        try {
            message = objectMapper.readValue(rawPayload, HeartbeatMessage.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Invalid MQTT heartbeat JSON: payload={}", summarize(rawPayload));
            return;
        }

        Set<ConstraintViolation<HeartbeatMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String invalidFields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining(","));
            log.warn(
                    "Invalid MQTT heartbeat values: fields={}, payload={}",
                    invalidFields,
                    summarize(rawPayload)
            );
            return;
        }

        if (!message.sentAt().toInstant().isBefore(clock.instant().plus(MAX_FUTURE_SKEW))) {
            log.warn(
                    "Invalid MQTT heartbeat sentAt: messageId={}, deviceId={}, sentAt={}",
                    message.messageId(),
                    message.deviceId(),
                    message.sentAt()
            );
            return;
        }

        if (!topicDeviceId.get().equals(message.deviceId())) {
            log.warn(
                    "MQTT heartbeat deviceId mismatch: topicDeviceId={}, payloadDeviceId={}",
                    topicDeviceId.get(),
                    message.deviceId()
            );
            return;
        }

        HeartbeatUpdateResult result = heartbeatService.recordHeartbeat(message.deviceId());
        if (result == HeartbeatUpdateResult.DEVICE_NOT_FOUND) {
            log.warn(
                    "MQTT heartbeat ignored because IoT device was not found: "
                            + "deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            return;
        }

        log.debug(
                "MQTT heartbeat processed: messageId={}, deviceId={}, sentAt={}",
                message.messageId(),
                message.deviceId(),
                message.sentAt()
        );
    }

    private String summarize(String payload) {
        String singleLine = payload.replace("\r", "\\r").replace("\n", "\\n");
        if (singleLine.length() <= MAX_LOG_PAYLOAD_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...";
    }
}
