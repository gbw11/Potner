package com.potner.location.dto;

import java.util.List;

public record RobotLocationListResponse(
        String robotId,
        List<RobotLocationResponse> locations
) {

    public RobotLocationListResponse {
        locations = List.copyOf(locations);
    }
}
