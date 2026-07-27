package com.potner.device.dto;

import java.util.List;

/** 사용자가 등록한 로봇 목록이다. 한 대도 없으면 빈 배열이다. */
public record RobotListResponse(List<RegisteredRobotResponse> robots) {
    public RobotListResponse {
        robots = List.copyOf(robots);
    }
}
