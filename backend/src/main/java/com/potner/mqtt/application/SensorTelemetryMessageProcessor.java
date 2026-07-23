package com.potner.mqtt.application;

import com.potner.mqtt.dto.SensorTelemetryMessage;
import com.potner.sensor.application.SensorReadingSaveResult;
import com.potner.sensor.application.SensorReadingService;
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
public class SensorTelemetryMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(SensorTelemetryMessageProcessor.class);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(10);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final SensorTelemetryTopicParser topicParser;
    private final SensorReadingService sensorReadingService;
    private final Clock clock;

    public SensorTelemetryMessageProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            SensorTelemetryTopicParser topicParser,
            SensorReadingService sensorReadingService,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.topicParser = topicParser;
        this.sensorReadingService = sensorReadingService;
        this.clock = clock;
    }

    public void process(String topic, String rawPayload) {
        Optional<String> topicDeviceId = topicParser.extractDeviceId(topic);
        if (topicDeviceId.isEmpty()) {
            log.warn("Invalid MQTT sensor telemetry topic: topic={}", topic);
            return;
        }

        SensorTelemetryMessage message;
        try {
            message = objectMapper.readValue(rawPayload, SensorTelemetryMessage.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Invalid MQTT sensor telemetry JSON: payload={}", summarize(rawPayload));
            return;
        }

        Set<ConstraintViolation<SensorTelemetryMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String invalidFields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining(","));
            log.warn(
                    "Invalid MQTT sensor telemetry values: fields={}, payload={}",
                    invalidFields,
                    summarize(rawPayload)
            );
            return;
        }

        if (!message.measuredAt().toInstant().isBefore(clock.instant().plus(MAX_FUTURE_SKEW))) {
            log.warn(
                    "Invalid MQTT sensor telemetry measuredAt: messageId={}, deviceId={}, measuredAt={}",
                    message.messageId(),
                    message.deviceId(),
                    message.measuredAt()
            );
            return;
        }

        if (!topicDeviceId.get().equals(message.deviceId())) {
            log.warn(
                    "MQTT sensor telemetry deviceId mismatch: topicDeviceId={}, payloadDeviceId={}",
                    topicDeviceId.get(),
                    message.deviceId()
            );
            return;
        }

        SensorReadingSaveResult result = sensorReadingService.save(message);
        switch (result) {
            case SAVED -> log.info(
                    "MQTT sensor readings saved: messageId={}, deviceId={}, topic={}, measuredAt={}",
                    message.messageId(),
                    message.deviceId(),
                    topic,
                    message.measuredAt()
            );
            case DUPLICATE -> log.debug(
                    "Duplicate MQTT sensor readings ignored: messageId={}, deviceId={}, topic={}",
                    message.messageId(),
                    message.deviceId(),
                    topic
            );
            case ROBOT_NOT_FOUND -> log.warn(
                    "MQTT sensor readings ignored because robot was not found: deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            case PLANT_ASSIGNMENT_NOT_FOUND -> log.warn(
                    "MQTT sensor readings ignored because active plant assignment was not found: "
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
