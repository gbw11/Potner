package com.potner.arrival.domain;

public enum ArrivalEventType {

    APPROACH("welcome_start"),
    CANCEL("welcome_cancel");

    private final String commandName;

    ArrivalEventType(String commandName) {
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }

    public static ArrivalEventType fromCommandName(String commandName) {
        for (ArrivalEventType type : values()) {
            if (type.commandName.equals(commandName)) {
                return type;
            }
        }
        return null;
    }
}
