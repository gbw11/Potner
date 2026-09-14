package com.potner.mqtt.application;

import com.potner.location.application.StationWaterLowService;
import com.potner.location.application.WaterLowUpdateResult;
import com.potner.mqtt.dto.WaterLowMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 물 부족 보고를 받아 반영한다.
 *
 * <p>센서·heartbeat·배터리 처리와 같은 순서를 지킨다. 토픽과 페이로드의 장치 식별자를
 * 대조하는 단계가 빠지면 안 된다. ACL 은 장치가 어느 토픽에 쓸 수 있는지만 제한하고
 * 페이로드 내용은 검사하지 못한다.
 */
@Component
public class WaterLowMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(WaterLowMessageProcessor.class);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(10);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final WaterLowTopicParser topicParser;
    private final StationWaterLowService stationWaterLowService;
    private final Clock clock;

    public WaterLowMessageProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            WaterLowTopicParser topicParser,
            StationWaterLowService stationWaterLowService,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.topicParser = topicParser;
        this.stationWaterLowService = stationWaterLowService;
        this.clock = clock;
    }

    public void process(String topic, String rawPayload) {
        Optional<String> topicDeviceId = topicParser.extractDeviceId(topic);
        if (topicDeviceId.isEmpty()) {
            log.warn("Invalid MQTT water-low topic: topic={}", topic);
            return;
        }

        WaterLowMessage message;
        try {
            message = objectMapper.readValue(rawPayload, WaterLowMessage.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Invalid MQTT water-low JSON: payload={}", summarize(rawPayload));
            return;
        }

        Set<ConstraintViolation<WaterLowMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String invalidFields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining(","));
            log.warn(
                    "Invalid MQTT water-low values: fields={}, payload={}",
                    invalidFields,
                    summarize(rawPayload)
            );
            return;
        }

        if (!message.measuredAt().toInstant().isBefore(clock.instant().plus(MAX_FUTURE_SKEW))) {
            log.warn(
                    "Invalid MQTT water-low measuredAt: messageId={}, deviceId={}, measuredAt={}",
                    message.messageId(),
                    message.deviceId(),
                    message.measuredAt()
            );
            return;
        }

        if (!topicDeviceId.get().equals(message.deviceId())) {
            log.warn(
                    "MQTT water-low deviceId mismatch: topicDeviceId={}, payloadDeviceId={}",
                    topicDeviceId.get(),
                    message.deviceId()
            );
            return;
        }

        LocalDateTime reportedAt = LocalDateTime.ofInstant(
                message.measuredAt().toInstant(),
                ZoneOffset.UTC
        );
        WaterLowUpdateResult result = stationWaterLowService.report(
                message.deviceId(),
                message.waterLow(),
                reportedAt
        );
        switch (result) {
            case BECAME_LOW -> log.info(
                    "Station became water-low: deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            case CLEARED -> log.info(
                    "Station water-low cleared: deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            // 반복 보고는 정상 흐름이다. 부족한 동안 주기 보고가 계속 오므로 INFO 로 남기면
            // 물을 채울 때까지 로그가 쏟아진다.
            case STILL_LOW, ALREADY_CLEAR -> log.debug(
                    "Station water-low unchanged: deviceId={}, waterLow={}",
                    message.deviceId(),
                    message.waterLow()
            );
            case DEVICE_NOT_FOUND -> log.warn(
                    "MQTT water-low ignored because IoT device was not found: deviceId={}, messageId={}",
                    message.deviceId(),
                    message.messageId()
            );
            case STATION_NOT_FOUND -> log.warn(
                    "MQTT water-low ignored because the robot has no water station registered: "
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
