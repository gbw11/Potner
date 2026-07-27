package com.potner.photo.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.DeviceUploadToken;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.photo.domain.PlantPhoto;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.photo.dto.PhotoListResponse;
import com.potner.photo.dto.PhotoResponse;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 사진 업로드와 기간 조회다.
 *
 * <p>업로드는 장치가 한다. 라즈베리는 사용자 JWT 를 가질 수 없으므로 업로드 토큰으로 인증하고,
 * 어느 식물인지는 요청이 아니라 <strong>로봇의 활성 배정에서 서버가 정한다</strong>.
 * 장치가 plantId 를 지정할 수 있으면 토큰 하나로 남의 식물에 사진을 넣을 수 있다.
 */
@Service
@Transactional(readOnly = true)
public class PhotoService {

    private final PlantPhotoRepository photoRepository;
    private final PlantRepository plantRepository;
    private final RobotRepository robotRepository;
    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final PhotoStorage photoStorage;
    private final DeviceUploadToken uploadTokenIssuer;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public PhotoService(
            PlantPhotoRepository photoRepository,
            PlantRepository plantRepository,
            RobotRepository robotRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            PhotoStorage photoStorage,
            DeviceUploadToken uploadTokenIssuer,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.photoRepository = photoRepository;
        this.plantRepository = plantRepository;
        this.robotRepository = robotRepository;
        this.assignmentRepository = assignmentRepository;
        this.photoStorage = photoStorage;
        this.uploadTokenIssuer = uploadTokenIssuer;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    /**
     * 장치가 올린 사진을 저장한다.
     *
     * <p>{@code capturedAt} 이 없으면 수신 시각을 쓴다. 오프셋을 포함해 보내야 하며,
     * 센서 {@code measuredAt} 과 같은 이유로 오프셋 없는 값은 받지 않는다.
     */
    @Transactional
    public PhotoResponse uploadFromDevice(
            String uploadToken,
            OffsetDateTime capturedAt,
            byte[] content
    ) {
        Robot robot = findRobotByToken(uploadToken);
        PlantDeviceAssignment assignment = assignmentRepository
                .findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(robot.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND));

        LocalDateTime capturedAtUtc = capturedAt == null
                ? nowUtc()
                : LocalDateTime.ofInstant(capturedAt.toInstant(), ZoneOffset.UTC);
        LocalDate photoDate = toServiceDate(capturedAtUtc);

        // 하루 한 장이 타임랩스 프레임 간격을 일정하게 유지한다. 같은 날 두 번 올리면 거부한다.
        if (photoRepository.existsByPlantIdAndPhotoDate(assignment.getPlantId(), photoDate)) {
            throw new BusinessException(ErrorCode.PHOTO_ALREADY_EXISTS_FOR_DATE);
        }

        String photoId = PlantPhoto.newPhotoId();
        PhotoStorage.StoredPhoto stored = photoStorage.store(
                assignment.getPlantId(),
                photoId,
                content
        );
        PlantPhoto photo = photoRepository.save(PlantPhoto.create(
                photoId,
                assignment.getPlantId(),
                robot.getId(),
                photoDate,
                capturedAtUtc,
                stored.paths(),
                stored.width(),
                stored.height(),
                stored.byteSize()
        ));
        return toResponse(photo);
    }

    /** 포토 로그와 타임랩스가 함께 쓰는 기간 조회다. */
    public PhotoListResponse getPhotos(String userId, String plantId, LocalDate from, LocalDate to) {
        requireOwnedPlant(userId, plantId);
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        List<PlantPhoto> photos = photoRepository
                .findAllByPlantIdAndPhotoDateBetweenOrderByCapturedAtAsc(plantId, from, to);
        int requestedDays = (int) ChronoUnit.DAYS.between(from, to) + 1;
        Set<LocalDate> capturedDates = new HashSet<>();
        photos.forEach(photo -> capturedDates.add(photo.getPhotoDate()));

        return new PhotoListResponse(
                plantId,
                sensorQueryProperties.zoneOffset(),
                from,
                to,
                requestedDays,
                capturedDates.size(),
                photos.stream().map(this::toResponse).toList()
        );
    }

    private PhotoResponse toResponse(PlantPhoto photo) {
        return new PhotoResponse(
                photo.getId(),
                photo.getPhotoDate(),
                photo.getCapturedAt(),
                photoStorage.toUrl(photo.getThumbnailPath()),
                photoStorage.toUrl(photo.getPlaybackPath()),
                photoStorage.toUrl(photo.getOriginalPath()),
                photo.getWidth(),
                photo.getHeight(),
                photo.getByteSize()
        );
    }

    /** 토큰이 비었거나 맞는 로봇이 없으면 401 이다. 어느 쪽인지는 구분해 알려주지 않는다. */
    private Robot findRobotByToken(String uploadToken) {
        if (uploadToken == null || uploadToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN);
        }
        return robotRepository.findByUploadTokenHash(uploadTokenIssuer.hash(uploadToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN));
    }

    /**
     * 서비스 타임존 기준 날짜다. {@code plant_daily_light.light_date} 와 같은 규칙이어야
     * 광량 이력과 사진 이력의 날짜가 어긋나지 않는다.
     */
    private LocalDate toServiceDate(LocalDateTime utc) {
        return utc.plusSeconds(sensorQueryProperties.zoneOffsetSeconds()).toLocalDate();
    }

    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
