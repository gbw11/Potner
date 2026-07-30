package com.potner.command.application;

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

/**
 * 명령 결과 회신을 받아 반영한다.
 *
 * <p>센서·heartbeat·배터리 처리와 같은 순서를 지킨다. 토픽과 페이로드의 장치 식별자 대조가
 * 빠지면 안 된다 — ACL 은 장치가 어느 토픽에 쓸 수 있는지만 제한하고 페이로드는 검사하지
 * 못하므로, 토픽만 믿으면 다른 장치의 requestId 를 실어 남의 명령을 끝낼 수 있다.
 */
@Component
public class CommandResultMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandResultMessageProcessor.class);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 500;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final CommandResultTopicParser topicParser;
    private final DeviceCommandResultService resultService;

    public CommandResultMessageProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            CommandResultTopicParser topicParser,
            DeviceCommandResultService resultService
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.topicParser = topicParser;
        this.resultService = resultService;
    }

    public void process(String topic, String rawPayload) {
        Optional<CommandResultTopicParser.ParsedResultTopic> parsed = topicParser.parse(topic);
        if (parsed.isEmpty()) {
            // 형식이 어긋났거나 서버가 모르는 명령 종류다. 장치가 결과를 먼저 붙이면 여기로 온다.
            log.warn("Invalid MQTT command result topic: topic={}", topic);
            return;
        }

        CommandResultMessage message;
        try {
            message = objectMapper.readValue(rawPayload, CommandResultMessage.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Invalid MQTT command result JSON: payload={}", summarize(rawPayload));
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
                    "Invalid MQTT command result values: fields={}, payload={}",
                    invalidFields,
                    summarize(rawPayload)
            );
            return;
        }

        if (message.requestId() == null || message.requestId().isBlank()) {
            // 서버가 requestId 없이 명령을 보내는 일은 없으므로, 이 회신은 서버 밖에서 온
            // 명령(수동 테스트 등)의 결과다. 대조할 수 없어 버리되 흔적은 남긴다.
            log.warn(
                    "MQTT command result ignored because requestId is missing: topic={}, messageId={}",
                    topic,
                    message.messageId()
            );
            return;
        }

        if (!parsed.get().deviceUid().equals(message.deviceId())) {
            log.warn(
                    "MQTT command result deviceId mismatch: topicDeviceId={}, payloadDeviceId={}",
                    parsed.get().deviceUid(),
                    message.deviceId()
            );
            return;
        }

        CommandResultApplyOutcome outcome = resultService.apply(
                parsed.get().deviceUid(),
                parsed.get().commandType(),
                message
        );
        switch (outcome) {
            case APPLIED -> log.info(
                    "Device command result applied: requestId={}, type={}, status={}, dispensedMl={}",
                    message.requestId(),
                    parsed.get().commandType(),
                    message.status(),
                    message.dispensedMl()
            );
            case DUPLICATE -> log.debug(
                    "Duplicate device command result ignored: requestId={}, messageId={}",
                    message.requestId(),
                    message.messageId()
            );
            case UNKNOWN_REQUEST -> log.warn(
                    "Device command result ignored because the request is unknown: requestId={}",
                    message.requestId()
            );
            case DEVICE_MISMATCH -> log.warn(
                    "Device command result ignored because the device does not match: "
                            + "requestId={}, reporterDeviceId={}",
                    message.requestId(),
                    message.deviceId()
            );
            case TYPE_MISMATCH -> log.warn(
                    "Device command result ignored because the command type does not match: "
                            + "requestId={}, topicType={}",
                    message.requestId(),
                    parsed.get().commandType()
            );
            case ALREADY_COMPLETED -> log.warn(
                    "Device command result ignored because another result was already applied: "
                            + "requestId={}, messageId={}",
                    message.requestId(),
                    message.messageId()
            );
            case INVALID_STATUS -> log.warn(
                    "Device command result ignored because the status is unknown: "
                            + "requestId={}, status={}",
                    message.requestId(),
                    message.status()
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
