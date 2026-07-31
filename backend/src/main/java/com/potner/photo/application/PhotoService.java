package com.potner.photo.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.DeviceUploadToken;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.photo.domain.PhotoSource;
import com.potner.photo.domain.PlantPhoto;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.photo.dto.PhotoListResponse;
import com.potner.photo.dto.PhotoResponse;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사진 업로드와 기간 조회, 그리고 식물 대표 사진이다.
 *
 * <p>업로드 경로가 둘이다. 장치는 사용자 JWT 를 가질 수 없으므로 업로드 토큰으로 인증하고,
 * 어느 식물인지는 요청이 아니라 <strong>로봇의 활성 배정에서 서버가 정한다</strong>.
 * 장치가 plantId 를 지정할 수 있으면 토큰 하나로 남의 식물에 사진을 넣을 수 있다.
 * 사용자는 JWT 로 인증하고 경로의 plantId 를 쓰되 소유권을 확인한다.
 *
 * <p>두 경로가 같은 테이블에 쓰지만 출처가 다르다. 포토 로그와 타임랩스는 장치 사진만 본다.
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
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public PhotoService(
            PlantPhotoRepository photoRepository,
            PlantRepository plantRepository,
            RobotRepository robotRepository,
            PlantDeviceAssignmentRepository assignmentRepository,
            PhotoStorage photoStorage,
            DeviceUploadToken uploadTokenIssuer,
            SensorQueryProperties sensorQueryProperties,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.photoRepository = photoRepository;
        this.plantRepository = plantRepository;
        this.robotRepository = robotRepository;
        this.assignmentRepository = assignmentRepository;
        this.photoStorage = photoStorage;
        this.uploadTokenIssuer = uploadTokenIssuer;
        this.sensorQueryProperties = sensorQueryProperties;
        this.eventPublisher = eventPublisher;
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
        // 출처를 함께 보므로 사용자가 대표 사진을 올린 날에도 장치는 그날 사진을 올릴 수 있다.
        if (photoRepository.existsByPlantIdAndSourceAndPhotoDate(
                assignment.getPlantId(),
                PhotoSource.DEVICE,
                photoDate)) {
            throw new BusinessException(ErrorCode.PHOTO_ALREADY_EXISTS_FOR_DATE);
        }

        String photoId = PlantPhoto.newPhotoId();
        PhotoStorage.StoredPhoto stored = photoStorage.store(
                assignment.getPlantId(),
                photoId,
                content
        );
        PlantPhoto photo = photoRepository.save(PlantPhoto.fromDevice(
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

        // 생장 단계 판정은 커밋 뒤 다른 스레드에서 돈다. 여기서 부르면 장치가 CPU 추론이 끝날
        // 때까지 업로드 응답을 기다린다. 촬영은 하루 한 번이고 판정이 몇 초 늦어도 무해하다.
        eventPublisher.publishEvent(new DevicePhotoUploadedEvent(photo.getId(), assignment.getPlantId()));
        return toResponse(photo);
    }

    /** 포토 로그와 타임랩스가 함께 쓰는 기간 조회다. 장치 사진만 나간다. */
    public PhotoListResponse getPhotos(String userId, String plantId, LocalDate from, LocalDate to) {
        requireOwnedPlant(userId, plantId);
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        List<PlantPhoto> photos = photoRepository
                .findAllByPlantIdAndSourceAndPhotoDateBetweenOrderByCapturedAtAsc(
                        plantId,
                        PhotoSource.DEVICE,
                        from,
                        to
                );
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

    /**
     * 사용자가 올린 사진을 대표 사진으로 지정한다.
     *
     * <p>하루 한 장 제한을 적용하지 않는다. 그 제한은 타임랩스 프레임 간격을 위한 것이고
     * 이 사진은 프레임이 아니다. 사용자는 대표 사진을 하루에도 여러 번 바꿀 수 있어야 한다.
     */
    @Transactional
    public PhotoResponse uploadRepresentativePhoto(String userId, String plantId, byte[] content) {
        Plant plant = requireOwnedPlant(userId, plantId);
        LocalDateTime now = nowUtc();

        String photoId = PlantPhoto.newPhotoId();
        PhotoStorage.StoredPhoto stored = photoStorage.store(plantId, photoId, content);
        PlantPhoto photo = photoRepository.save(PlantPhoto.fromUser(
                photoId,
                plantId,
                toServiceDate(now),
                now,
                stored.paths(),
                stored.width(),
                stored.height(),
                stored.byteSize()
        ));

        replaceRepresentative(plant, photo.getId(), now);
        return toResponse(photo);
    }

    /**
     * 포토 로그에 있는 장치 사진을 대표로 고른다.
     *
     * <p>그 식물의 사진인지 확인한다. 확인하지 않으면 남의 식물 사진을 자기 대표로 걸 수 있다.
     * 대표 사진 URL 은 인증 없이 열리므로 그대로 유출 경로가 된다.
     */
    @Transactional
    public PhotoResponse selectRepresentativePhoto(String userId, String plantId, String photoId) {
        Plant plant = requireOwnedPlant(userId, plantId);
        PlantPhoto photo = photoRepository.findByIdAndPlantId(photoId, plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PHOTO_NOT_FOUND));

        replaceRepresentative(plant, photo.getId(), nowUtc());
        return toResponse(photo);
    }

    /** 대표 사진을 없앤다. 사용자가 올린 사진이었으면 함께 지운다. */
    @Transactional
    public void clearRepresentativePhoto(String userId, String plantId) {
        Plant plant = requireOwnedPlant(userId, plantId);
        replaceRepresentative(plant, null, nowUtc());
    }

    /**
     * 그날 장치가 찍은 사진이다. 일기 상세가 사진을 함께 보여줄 때 쓴다.
     *
     * <p>일기가 사진을 따로 갖지 않는 이유다. 장치가 하루 한 장 찍으므로 날짜만 알면 찾을 수
     * 있고, 사용자가 일기에 따로 올리게 하면 같은 날 사진이 둘 생겨 하루 한 장 정책과 충돌한다.
     */
    public Optional<PhotoResponse> findDevicePhotoOfDate(String plantId, LocalDate photoDate) {
        return photoRepository
                .findByPlantIdAndSourceAndPhotoDate(plantId, PhotoSource.DEVICE, photoDate)
                .map(this::toResponse);
    }

    /** 일기 목록이 날짜별 썸네일을 한 번에 채우는 데 쓴다. 사진이 없는 날은 결과에서 빠진다. */
    public Map<LocalDate, PhotoResponse> findDevicePhotosOfDates(
            String plantId,
            Collection<LocalDate> photoDates
    ) {
        if (photoDates.isEmpty()) {
            return Map.of();
        }
        return photoRepository
                .findAllByPlantIdAndSourceAndPhotoDateIn(plantId, PhotoSource.DEVICE, photoDates)
                .stream()
                .collect(Collectors.toMap(PlantPhoto::getPhotoDate, this::toResponse));
    }

    /**
     * 식물 목록·상세가 대표 사진을 채우는 데 쓴다. 없는 식별자는 결과에서 빠진다.
     *
     * <p>목록이 식물마다 조회하지 않도록 한 번에 받는다.
     */
    public Map<String, PhotoResponse> findResponsesByIds(Collection<String> photoIds) {
        List<String> present = photoIds.stream().filter(Objects::nonNull).distinct().toList();
        if (present.isEmpty()) {
            return Map.of();
        }
        return photoRepository.findAllByIdIn(present).stream()
                .collect(Collectors.toMap(PlantPhoto::getId, this::toResponse));
    }

    /**
     * 대표 사진을 갈아끼우고 필요하면 이전 것을 정리한다.
     *
     * <p>이전 사진이 사용자 업로드였으면 행과 파일을 지운다. 포토 로그에 보이지 않고 대표도
     * 아니게 되면 어디서도 닿을 수 없기 때문이다. 장치 사진은 포토 로그와 타임랩스가 계속
     * 참조하므로 대표에서 내려올 뿐 지우지 않는다.
     *
     * <p>식물을 먼저 갱신하고 사진을 지운다. 순서가 반대면 {@code plant} 가 아직 그 사진을
     * 가리키는 상태에서 삭제가 나가 외래키에 걸린다. Hibernate 가 update 를 delete 보다 먼저
     * 내보내지만, 의도를 분명히 하려고 명시적으로 flush 한다.
     */
    private void replaceRepresentative(Plant plant, String newPhotoId, LocalDateTime now) {
        String previousPhotoId = plant.getRepresentativePhotoId();
        if (Objects.equals(previousPhotoId, newPhotoId)) {
            return;
        }

        plant.changeRepresentativePhoto(newPhotoId, now);
        plantRepository.flush();

        if (previousPhotoId == null) {
            return;
        }
        photoRepository.findById(previousPhotoId)
                .filter(PlantPhoto::isUserUploaded)
                .ifPresent(previous -> {
                    photoRepository.delete(previous);
                    photoRepository.flush();
                    photoStorage.delete(previous.getPlantId(), previous.getId());
                });
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
        return robotRepository.findByUploadTokenHashAndReleasedAtIsNull(uploadTokenIssuer.hash(uploadToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_TOKEN));
    }

    /**
     * 서비스 타임존 기준 날짜다. {@code plant_daily_light.light_date} 와 같은 규칙이어야
     * 광량 이력과 사진 이력의 날짜가 어긋나지 않는다.
     */
    private LocalDate toServiceDate(LocalDateTime utc) {
        return utc.plusSeconds(sensorQueryProperties.zoneOffsetSeconds()).toLocalDate();
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private Plant requireOwnedPlant(String userId, String plantId) {
        return plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
