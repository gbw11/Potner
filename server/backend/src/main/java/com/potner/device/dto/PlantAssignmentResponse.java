package com.potner.device.dto;

import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.Robot;

import java.time.LocalDateTime;

/** 배정 결과다. 배정된 뒤부터 그 로봇이 보내는 측정값이 이 식물로 저장된다. */
public record PlantAssignmentResponse(
        String plantId,
        String robotId,
        String robotDeviceUid,
        String robotName,
        LocalDateTime assignedAt
) {
    public static PlantAssignmentResponse of(PlantDeviceAssignment assignment, Robot robot) {
        return new PlantAssignmentResponse(
                assignment.getPlantId(),
                robot.getId(),
                robot.getDeviceUid(),
                robot.getName(),
                assignment.getAssignedAt()
        );
    }
}
