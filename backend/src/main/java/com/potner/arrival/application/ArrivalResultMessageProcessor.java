package com.potner.arrival.application;

import com.potner.command.application.CommandResultApplyOutcome;
import com.potner.command.dto.CommandResultMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ArrivalResultMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ArrivalResultMessageProcessor.class);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ArrivalResultTopicParser topicParser;
    private final ArrivalResultService resultService;

    public ArrivalResultMessageProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            ArrivalResultTopicParser topicParser,
            ArrivalResultService resultService
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.topicParser = topicParser;
        this.resultService = resultService;
    }

    public void process(String topic, String rawPayload) {
        Optional<ArrivalResultTopicParser.ParsedArrivalResultTopic> parsed =
                topicParser.parse(topic);
        if (parsed.isEmpty()) {
            log.warn("Invalid MQTT arrival result topic: topic={}", topic);
            return;
        }

        CommandResultMessage message;
        try {
            message = objectMapper.readValue(rawPayload, CommandResultMessage.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Invalid MQTT arrival result JSON: payload={}", summarize(rawPayload));
            return;
        }

        Set<ConstraintViolation<CommandResultMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String invalidFields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining(","));
            log.warn(
                    "Invalid MQTT arrival result values: fields={}, payload={}",
                    invalidFields,
                    summarize(rawPayload)
            );
            return;
        }
        if (message.requestId() == null || message.requestId().isBlank()) {
            log.warn(
                    "MQTT arrival result ignored because requestId is missing: "
                            + "topic={}, messageId={}",
                    topic,
                    message.messageId()
            );
            return;
        }
        if (!parsed.get().deviceUid().equals(message.deviceId())) {
            log.warn(
                    "MQTT arrival result deviceId mismatch: topicDeviceId={}, payloadDeviceId={}",
                    parsed.get().deviceUid(),
                    message.deviceId()
            );
            return;
        }

        CommandResultApplyOutcome outcome = resultService.apply(
                parsed.get().deviceUid(),
                parsed.get().eventType(),
                message
        );
        if (outcome == CommandResultApplyOutcome.APPLIED) {
            log.info(
                    "Arrival result applied: eventId={}, type={}, status={}",
                    message.requestId(),
                    parsed.get().eventType(),
                    message.status()
            );
        } else if (outcome != CommandResultApplyOutcome.DUPLICATE) {
            log.warn(
                    "Arrival result ignored: eventId={}, type={}, outcome={}",
                    message.requestId(),
                    parsed.get().eventType(),
                    outcome
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
