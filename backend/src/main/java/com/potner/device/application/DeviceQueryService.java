package com.potner.device.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceConnectionStatus;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.device.dto.IotDeviceResponse;
import com.potner.device.dto.PlantDeviceListResponse;
import com.potner.device.dto.RobotSummaryResponse;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
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
        RobotSummaryResponse robotSummary = new RobotSummaryResponse(
                robot.getId(),
                robot.getName(),
                deriveConnectionStatus(devices),
                deriveLastSeenAt(devices),
                robot.getBatteryPercent(),
                robot.getFirmwareVersion(),
                assignment.getAssignedAt()
        );
        return new PlantDeviceListResponse(
                plantId,
                robotSummary,
                devices.stream().map(IotDeviceResponse::from).toList()
        );
    }

    /**
     * 로봇의 연결 상태는 저장하지 않고 하위 장치에서 계산한다.
     *
     * <p>Heartbeat는 {@code iot_device}만 갱신하므로 {@code robot.connection_status}는
     * 갱신되지 않는다. 같은 사실을 두 곳에 저장하면 어긋날 수 있으므로 장치 상태를 유일한 근거로 삼는다.
     * 장치 하나라도 응답하고 있으면 로봇에 도달할 수 있다는 뜻이다.
     */
    private IotDeviceConnectionStatus deriveConnectionStatus(List<IotDevice> devices) {
        boolean anyOnline = devices.stream()
                .anyMatch(device -> device.getConnectionStatus() == IotDeviceConnectionStatus.ONLINE);
        return anyOnline
                ? IotDeviceConnectionStatus.ONLINE
                : IotDeviceConnectionStatus.OFFLINE;
    }

    /** 장치들이 마지막으로 응답한 시각 중 가장 최근값이다. */
    private LocalDateTime deriveLastSeenAt(List<IotDevice> devices) {
        return devices.stream()
                .map(IotDevice::getLastSeenAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private void requireOwnedPlant(String userId, String plantId) {
        Optional<?> owned = plantRepository
                .findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED);
        if (owned.isEmpty()) {
            throw new BusinessException(ErrorCode.PLANT_NOT_FOUND);
        }
    }
}
