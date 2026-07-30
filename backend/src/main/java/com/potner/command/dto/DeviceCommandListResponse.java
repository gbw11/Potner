package com.potner.command.dto;

import java.util.List;

public record DeviceCommandListResponse(
        String plantId,
        List<DeviceCommandResponse> commands
) {

    public DeviceCommandListResponse {
        commands = List.copyOf(commands);
    }
}
