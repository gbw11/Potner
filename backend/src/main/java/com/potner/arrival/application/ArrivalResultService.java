package com.potner.arrival.application;

import com.potner.arrival.domain.ArrivalEvent;
import com.potner.arrival.domain.ArrivalEventRepository;
import com.potner.arrival.domain.ArrivalEventType;
import com.potner.arrival.domain.ArrivalProcessingStatus;
import com.potner.command.application.CommandResultApplyOutcome;
import com.potner.command.dto.CommandResultMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class ArrivalResultService {

    private final ArrivalEventRepository arrivalEventRepository;
    private final Clock clock;

    public ArrivalResultService(ArrivalEventRepository arrivalEventRepository, Clock clock) {
        this.arrivalEventRepository = arrivalEventRepository;
        this.clock = clock;
    }

    @Transactional
    public CommandResultApplyOutcome apply(
            String topicDeviceUid,
            ArrivalEventType topicEventType,
            CommandResultMessage message
    ) {
        ArrivalEvent event = arrivalEventRepository.findById(message.requestId()).orElse(null);
        if (event == null) {
            return CommandResultApplyOutcome.UNKNOWN_REQUEST;
        }
        if (!event.getDeviceUid().equals(topicDeviceUid)) {
            return CommandResultApplyOutcome.DEVICE_MISMATCH;
        }
        if (event.getEventType() != topicEventType) {
            return CommandResultApplyOutcome.TYPE_MISMATCH;
        }
        if (message.messageId().toString().equals(event.getResultMessageId())) {
            return CommandResultApplyOutcome.DUPLICATE;
        }
        if (event.getProcessingStatus().isTerminal()) {
            return CommandResultApplyOutcome.ALREADY_COMPLETED;
        }

        Optional<ArrivalProcessingStatus> status =
                ArrivalProcessingStatus.fromDeviceReport(message.status());
        if (status.isEmpty()) {
            return CommandResultApplyOutcome.INVALID_STATUS;
        }

        event.applyResult(
                status.get(),
                errorMessage(message),
                message.messageId().toString(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        );
        return CommandResultApplyOutcome.APPLIED;
    }

    private String errorMessage(CommandResultMessage message) {
        if (message.code() != null && !message.code().isBlank()) {
            return message.error() == null
                    ? message.code()
                    : message.code() + ": " + message.error();
        }
        return message.error();
    }
}
