package com.potner.arrival.domain;

import java.util.Optional;

public enum ArrivalProcessingStatus {
    COMMAND_PUBLISHED,
    OK,
    ERROR,
    BUSY,
    TIMED_OUT;

    public static Optional<ArrivalProcessingStatus> fromDeviceReport(String reported) {
        if (reported == null) {
            return Optional.empty();
        }
        return switch (reported) {
            case "OK" -> Optional.of(OK);
            case "ERROR" -> Optional.of(ERROR);
            case "BUSY" -> Optional.of(BUSY);
            default -> Optional.empty();
        };
    }

    public boolean isTerminal() {
        return this == OK || this == ERROR || this == BUSY;
    }
}
