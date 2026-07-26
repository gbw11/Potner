package com.potner.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlantDeviceAssignmentRepository extends JpaRepository<PlantDeviceAssignment, String> {

    Optional<PlantDeviceAssignment> findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(
            String robotId
    );

    Optional<PlantDeviceAssignment> findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(
            String plantId
    );
}
