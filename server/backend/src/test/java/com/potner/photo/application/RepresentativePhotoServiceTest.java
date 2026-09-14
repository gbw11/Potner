package com.potner.photo.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.DeviceUploadToken;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.RobotRepository;
import com.potner.photo.config.PhotoStorageProperties;
import com.potner.photo.domain.PlantPhoto;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.photo.domain.StoredPhotoPaths;
import com.potner.photo.dto.PhotoResponse;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepresentativePhotoServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");
    private static final byte[] CONTENT = {1, 2, 3};

    @Mock
    private PlantPhotoRepository photoRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private RobotRepository robotRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private PhotoStorage photoStorage;

    @Mock
    private DeviceUploadToken uploadTokenIssuer;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PhotoService photoService;

    @BeforeEach
    void setUp() {
        photoService = new PhotoService(
                photoRepository,
                plantRepository,
                robotRepository,
                assignmentRepository,
                photoStorage,
                uploadTokenIssuer,
                new SensorQueryProperties("+09:00", 15, 14, 365),
                new PhotoStorageProperties("./data/photos", "/media", 1280, 400, 10485760L, 180, true),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        lenient().when(photoStorage.toUrl(anyString())).thenAnswer(call -> "/media/" + call.getArgument(0));
    }

    @Test
    void uploadedPhotoBecomesTheRepresentative() {
        Plant plant = givenOwnedPlant(null);
        givenStoredPhoto();

        PhotoResponse response = photoService.uploadRepresentativePhoto(USER_ID, PLANT_ID, CONTENT);

        verify(plant).changeRepresentativePhoto(eq(response.photoId()), any(LocalDateTime.class));
        // 사용자 사진은 타임랩스 프레임이 아니므로 하루 한 장 제한을 보지 않는다.
        verify(photoRepository, never()).existsByPlantIdAndSourceAndPhotoDate(any(), any(), any());
    }

    @Test
    void previousUserPhotoIsDeletedWhenReplaced() {
        PlantPhoto previous = userPhoto("photo-old");
        givenOwnedPlant("photo-old");
        givenStoredPhoto();
        when(photoRepository.findById("photo-old")).thenReturn(Optional.of(previous));

        photoService.uploadRepresentativePhoto(USER_ID, PLANT_ID, CONTENT);

        // 포토 로그에도 안 보이고 대표도 아니면 어디서도 닿을 수 없다. 행과 파일을 함께 지운다.
        verify(photoRepository).delete(previous);
        verify(photoStorage).delete(PLANT_ID, "photo-old");
    }

    @Test
    void previousDevicePhotoIsKeptWhenReplaced() {
        PlantPhoto previous = devicePhoto("photo-device");
        givenOwnedPlant("photo-device");
        givenStoredPhoto();
        when(photoRepository.findById("photo-device")).thenReturn(Optional.of(previous));

        photoService.uploadRepresentativePhoto(USER_ID, PLANT_ID, CONTENT);

        // 포토 로그와 타임랩스가 계속 참조한다. 대표에서 내려올 뿐이다.
        verify(photoRepository, never()).delete(previous);
        verify(photoStorage, never()).delete(any(), eq("photo-device"));
    }

    @Test
    void selectingAPhotoOfAnotherPlantIsRejected() {
        givenOwnedPlant(null);
        when(photoRepository.findByIdAndPlantId("photo-elsewhere", PLANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                photoService.selectRepresentativePhoto(USER_ID, PLANT_ID, "photo-elsewhere"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PHOTO_NOT_FOUND);
    }

    @Test
    void selectingTheSamePhotoAgainChangesNothing() {
        PlantPhoto current = devicePhoto("photo-same");
        Plant plant = givenOwnedPlant("photo-same");
        when(photoRepository.findByIdAndPlantId("photo-same", PLANT_ID))
                .thenReturn(Optional.of(current));

        photoService.selectRepresentativePhoto(USER_ID, PLANT_ID, "photo-same");

        verify(plant, never()).changeRepresentativePhoto(any(), any());
        verify(photoRepository, never()).delete(any());
    }

    @Test
    void clearingRemovesTheUserPhoto() {
        PlantPhoto previous = userPhoto("photo-old");
        Plant plant = givenOwnedPlant("photo-old");
        when(photoRepository.findById("photo-old")).thenReturn(Optional.of(previous));

        photoService.clearRepresentativePhoto(USER_ID, PLANT_ID);

        verify(plant).changeRepresentativePhoto(eq(null), any(LocalDateTime.class));
        verify(photoRepository).delete(previous);
        verify(photoStorage).delete(PLANT_ID, "photo-old");
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        // 존재 여부를 숨기려고 403 이 아니라 404 다.
        assertThatThrownBy(() ->
                photoService.uploadRepresentativePhoto(USER_ID, PLANT_ID, CONTENT))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PLANT_NOT_FOUND);
        verify(photoStorage, never()).store(any(), any(), any());
    }

    @Test
    void deletingAPhotoRemovesTheRowAndTheFile() {
        givenOwnedPlant(null);
        PlantPhoto photo = userPhoto("photo-1");
        when(photoRepository.findByIdAndPlantId("photo-1", PLANT_ID))
                .thenReturn(Optional.of(photo));

        photoService.deletePhoto(USER_ID, PLANT_ID, "photo-1");

        // 행을 먼저 지우고 파일을 지운다. 순서가 바뀌면 DB 삭제 실패 시 목록에 깨진 이미지가 남는다.
        verify(photoRepository).delete(photo);
        verify(photoStorage).delete(PLANT_ID, "photo-1");
    }

    @Test
    void deletingTheRepresentativePhotoClearsTheReference() {
        // DB 는 ON DELETE SET NULL 로 정리하지만, 같은 트랜잭션의 엔티티는 옛 값을 들고 있다.
        Plant plant = givenOwnedPlant("photo-1");
        when(photoRepository.findByIdAndPlantId("photo-1", PLANT_ID))
                .thenReturn(Optional.of(userPhoto("photo-1")));

        photoService.deletePhoto(USER_ID, PLANT_ID, "photo-1");

        verify(plant).changeRepresentativePhoto(eq(null), any());
    }

    @Test
    void deletingAPhotoOfAnotherPlantIsNotFound() {
        givenOwnedPlant(null);
        when(photoRepository.findByIdAndPlantId("photo-1", PLANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> photoService.deletePhoto(USER_ID, PLANT_ID, "photo-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PHOTO_NOT_FOUND);
        verify(photoStorage, never()).delete(any(), any());
    }

    private Plant givenOwnedPlant(String representativePhotoId) {
        Plant plant = mock(Plant.class);
        lenient().when(plant.getRepresentativePhotoId()).thenReturn(representativePhotoId);
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(plant));
        return plant;
    }

    private void givenStoredPhoto() {
        StoredPhotoPaths paths = new StoredPhotoPaths("o.jpg", "p.jpg", "t.jpg");
        when(photoStorage.store(eq(PLANT_ID), anyString(), eq(CONTENT)))
                .thenReturn(new PhotoStorage.StoredPhoto(paths, 100, 80, 3));
        when(photoRepository.save(any(PlantPhoto.class))).thenAnswer(call -> call.getArgument(0));
    }

    private PlantPhoto userPhoto(String photoId) {
        return PlantPhoto.fromUser(
                photoId,
                PLANT_ID,
                LocalDate.of(2026, 7, 28),
                LocalDateTime.of(2026, 7, 28, 1, 0),
                new StoredPhotoPaths("o.jpg", "p.jpg", "t.jpg"),
                100,
                80,
                3
        );
    }

    private PlantPhoto devicePhoto(String photoId) {
        return PlantPhoto.fromDevice(
                photoId,
                PLANT_ID,
                "robot-id",
                LocalDate.of(2026, 7, 28),
                LocalDateTime.of(2026, 7, 28, 1, 0),
                new StoredPhotoPaths("o.jpg", "p.jpg", "t.jpg"),
                100,
                80,
                3
        );
    }
}
