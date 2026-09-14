package com.potner.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 식물과 담당 로봇의 배정 이력이다.
 *
 * <p>{@code active_plant_key}, {@code active_robot_key} 생성 컬럼은 매핑하지 않는다.
 * DB가 {@code unassigned_at} 에서 파생하며 활성 배정이 식물·로봇당 하나임을 보장한다.
 * 해제하면 파생 키가 NULL 이 되어 UNIQUE 대상에서 빠지므로 재배정이 되고 이력도 남는다.
 */
@Entity
@Table(name = "plant_device_assignment")
public class PlantDeviceAssignment {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "assignment_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String robotId;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "unassigned_at")
    private LocalDateTime unassignedAt;

    protected PlantDeviceAssignment() {
    }

    public static PlantDeviceAssignment assign(
            String plantId,
            String robotId,
            LocalDateTime assignedAt
    ) {
        PlantDeviceAssignment assignment = new PlantDeviceAssignment();
        assignment.id = UUID.randomUUID().toString();
        assignment.plantId = plantId;
        assignment.robotId = robotId;
        assignment.assignedAt = assignedAt;
        return assignment;
    }

    /** 행을 지우지 않고 해제 시각만 남긴다. 배정 이력을 보존하기 위함이다. */
    public void unassign(LocalDateTime unassignedAt) {
        this.unassignedAt = unassignedAt;
    }

    public String getId() {
        return id;
    }

    public String getPlantId() {
        return plantId;
    }

    public String getRobotId() {
        return robotId;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public LocalDateTime getUnassignedAt() {
        return unassignedAt;
    }
}
