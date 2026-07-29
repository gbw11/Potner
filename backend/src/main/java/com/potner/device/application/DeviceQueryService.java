package com.potner.device.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotConnectionState;
import com.potner.device.domain.RobotRepository;
import com.potner.device.dto.IotDeviceResponse;
import com.potner.device.dto.PlantDeviceListResponse;
import com.potner.device.dto.RobotSummaryResponse;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class DeviceQueryService {

    private final PlantRepository plantRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final RobotRepository robotRepository;
    private final IotDeviceRepository iotDeviceRepository;

    public DeviceQueryService(
            PlantRepository plantRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            RobotRepository robotRepository,
            IotDeviceRepository iotDeviceRepository
    ) {
        this.plantRepository = plantRepository;
        this.assignmentRepository = assignmentRepository;
        this.robotRepository = robotRepository;
        this.iotDeviceRepository = iotDeviceRepository;
    }

    public PlantDeviceListResponse getPlantDevices(String userId, String plantId) {
        requireOwnedPlant(userId, plantId);

        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(plantId)
                .orElse(null);
        if (assignment == null) {
            return PlantDeviceListResponse.unassigned(plantId);
        }
        Robot robot = robotRepository.findById(assignment.getRobotId()).orElse(null);
        if (robot == null) {
            return PlantDeviceListResponse.unassigned(plantId);
        }

        List<IotDevice> devices = iotDeviceRepository
                .findAllByRobotIdOrderByDeviceTypeAsc(robot.getId());
        RobotConnectionState connection = RobotConnectionState.derive(devices);
        RobotSummaryResponse robotSummary = new RobotSummaryResponse(
                robot.getId(),
                robot.getName(),
                connection.status(),
                connection.lastSeenAt(),
                robot.getBatteryPercent(),
                robot.getBatteryMeasuredAt(),
                robot.getFirmwareVersion(),
                robot.getCurrentState(),
                robot.getStateChangedAt(),
                assignment.getAssignedAt()
        );
        return new PlantDeviceListResponse(
                plantId,
                robotSummary,
                devices.stream().map(IotDeviceResponse::from).toList()
        );
    }

    private void requireOwnedPlant(String userId, String plantId) {
        Optional<?> owned = plantRepository
                .findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED);
        if (owned.isEmpty()) {
            throw new BusinessException(ErrorCode.PLANT_NOT_FOUND);
        }
    }
}
