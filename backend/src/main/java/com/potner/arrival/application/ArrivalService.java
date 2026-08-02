package com.potner.arrival.application;

import com.potner.arrival.config.ArrivalProperties;
import com.potner.arrival.domain.ArrivalEvent;
import com.potner.arrival.domain.ArrivalEventRepository;
import com.potner.arrival.domain.ArrivalEventType;
import com.potner.arrival.dto.ArrivalEventRequest;
import com.potner.arrival.dto.ArrivalEventResponse;
import com.potner.arrival.dto.ArrivalEventStatusResponse;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class ArrivalService {

    private final ArrivalEventRepository arrivalEventRepository;
    private final RobotRepository robotRepository;
    private final IotDeviceRepository iotDeviceRepository;
    private final RobotLocationRepository locationRepository;
    private final ArrivalProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ArrivalService(
            ArrivalEventRepository arrivalEventRepository,
            RobotRepository robotRepository,
            IotDeviceRepository iotDeviceRepository,
            RobotLocationRepository locationRepository,
            ArrivalProperties properties,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.arrivalEventRepository = arrivalEventRepository;
        this.robotRepository = robotRepository;
        this.iotDeviceRepository = iotDeviceRepository;
        this.locationRepository = locationRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public ArrivalEventResponse process(String userId, ArrivalEventRequest request) {
        String eventId = request.eventId().toString();
        if (arrivalEventRepository.existsById(eventId)) {
            return ArrivalEventResponse.duplicate(request.eventId());
        }
        validateEventTime(request.occurredAt());

        Robot robot = singleRobot(userId);
        IotDevice jetson = iotDeviceRepository
                .findAllByRobotIdOrderByDeviceTypeAsc(robot.getId())
                .stream()
                .filter(device -> device.getReleasedAt() == null)
                .filter(device -> device.getDeviceType() == IotDeviceType.JETSON_ORIN)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_DEVICE_NOT_FOUND));

        if (request.eventType() == ArrivalEventType.CANCEL
                && !arrivalEventRepository.existsByVisitIdAndUserIdAndEventType(
                        request.visitId().toString(), userId, ArrivalEventType.APPROACH)) {
            throw new BusinessException(ErrorCode.ARRIVAL_VISIT_NOT_FOUND);
        }

        RobotLocation home = requirePose(robot.getId(), RobotLocationType.HOME);
        RobotLocation greeting = request.eventType() == ArrivalEventType.APPROACH
                ? requirePose(robot.getId(), RobotLocationType.GREETING)
                : null;

        ArrivalEvent saved = arrivalEventRepository.save(ArrivalEvent.received(
                eventId,
                request.visitId().toString(),
                userId,
                robot.getId(),
                jetson.getDeviceUid(),
                request.eventType(),
                request.source(),
                request.geofenceId(),
                LocalDateTime.ofInstant(request.occurredAt().toInstant(), ZoneOffset.UTC)
        ));

        eventPublisher.publishEvent(new ArrivalCommandIssuedEvent(
                saved.getEventId(),
                saved.getVisitId(),
                jetson.getDeviceUid(),
                saved.getEventType(),
                greeting == null ? null : greeting.getPoseX(),
                greeting == null ? null : greeting.getPoseY(),
                greeting == null ? null : greeting.getPoseYaw(),
                home.getPoseX(),
                home.getPoseY(),
                home.getPoseYaw(),
                properties.welcomeWaitSeconds(),
                properties.totalTimeoutSeconds(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        ));
        return ArrivalEventResponse.published(request.eventId());
    }

    @Transactional(readOnly = true)
    public ArrivalEventStatusResponse getStatus(String userId, String eventId) {
        ArrivalEvent event = arrivalEventRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARRIVAL_EVENT_NOT_FOUND));
        return ArrivalEventStatusResponse.from(event);
    }

    private Robot singleRobot(String userId) {
        List<Robot> robots =
                robotRepository.findAllByUserIdAndReleasedAtIsNullOrderByNameAsc(userId);
        if (robots.isEmpty()) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        if (robots.size() > 1) {
            throw new BusinessException(ErrorCode.ARRIVAL_MULTIPLE_ROBOTS);
        }
        return robots.getFirst();
    }

    private void validateEventTime(OffsetDateTime occurredAt) {
        var occurredInstant = occurredAt.toInstant();
        var now = clock.instant();
        if (occurredInstant.isBefore(now.minusSeconds(properties.eventMaxAgeSeconds()))) {
            throw new BusinessException(ErrorCode.STALE_EVENT);
        }
        if (occurredInstant.isAfter(now.plusSeconds(properties.futureEventSkewSeconds()))) {
            throw new BusinessException(ErrorCode.INVALID_EVENT_TIME);
        }
    }

    private RobotLocation requirePose(String robotId, RobotLocationType type) {
        RobotLocation location = locationRepository.findByRobotIdAndLocationType(robotId, type)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_LOCATION_NOT_FOUND));
        if (!location.hasPose()) {
            throw new BusinessException(ErrorCode.LOCATION_POSE_NOT_CONFIGURED);
        }
        return location;
    }
}
