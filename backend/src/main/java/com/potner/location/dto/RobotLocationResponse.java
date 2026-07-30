package com.potner.location.dto;

import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 위치 한 곳의 현재 상태다.
 *
 * <p>좌표가 {@code null} 이면 아직 설치자가 넣지 않은 것이다. {@code poseConfigured} 를 따로
 * 주는 이유는 앱이 세 값의 null 여부를 각각 보게 하지 않기 위해서다 — 셋은 항상 함께 있거나
 * 함께 없다.
 */
public record RobotLocationResponse(
        String locationId,
        RobotLocationType type,
        String stationCode,
        BigDecimal poseX,
        BigDecimal poseY,
        BigDecimal poseYaw,
        boolean poseConfigured,
        boolean waterLow,
        LocalDateTime waterLowAt
) {

    public static RobotLocationResponse from(RobotLocation location) {
        return new RobotLocationResponse(
                location.getId(),
                location.getLocationType(),
                location.getStationCode(),
                location.getPoseX(),
                location.getPoseY(),
                location.getPoseYaw(),
                location.hasPose(),
                location.isWaterLow(),
                location.getWaterLowAt()
        );
    }
}
