package com.potner.diary.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.diary.domain.PlantDiary;
import com.potner.diary.domain.PlantDiaryRepository;
import com.potner.diary.dto.DiaryListResponse;
import com.potner.photo.application.PhotoService;
import com.potner.photo.dto.PhotoResponse;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiaryServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    @Mock
    private PlantDiaryRepository diaryRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PhotoService photoService;

    private DiaryService diaryService;

    @BeforeEach
    void setUp() {
        diaryService = new DiaryService(diaryRepository, plantRepository, photoService);
    }

    @Test
    void writesOneDiaryPerDay() {
        when(diaryRepository.existsByPlantIdAndDiaryDate(PLANT_ID, DATE)).thenReturn(false);

        assertThat(diaryService.write(PLANT_ID, DATE, "첫 잎", "오늘은 잎이 하나 났어요."))
                .isEqualTo(DiaryWriteResult.WRITTEN);
        verify(diaryRepository).save(any(PlantDiary.class));
    }

    @Test
    void rerunningTheBatchDoesNotOverwriteWhatIsAlreadyThere() {
        when(diaryRepository.existsByPlantIdAndDiaryDate(PLANT_ID, DATE)).thenReturn(true);

        // 예외 대신 결과로 알린다. 배치가 여러 식물을 도는 중이라 한 건의 예외로 멈추면 안 된다.
        assertThat(diaryService.write(PLANT_ID, DATE, "덮어쓰기", "새 내용"))
                .isEqualTo(DiaryWriteResult.SKIPPED_ALREADY_WRITTEN);
        // 멀쩡한 일기를 갈아치우면 사용자가 읽던 내용이 소리 없이 바뀐다.
        verify(diaryRepository, never()).save(any());
    }

    @Test
    void listCarriesTitlesAndThumbnailsButNotContent() {
        givenOwnedPlant();
        PlantDiary diary = PlantDiary.write(PLANT_ID, DATE, "첫 잎", "본문은 상세에서 읽는다.");
        when(diaryRepository.findAllByPlantIdAndDiaryDateBetweenOrderByDiaryDateDesc(
                PLANT_ID, DATE, DATE)).thenReturn(List.of(diary));
        when(photoService.findDevicePhotosOfDates(PLANT_ID, List.of(DATE)))
                .thenReturn(Map.of(DATE, photoResponse()));

        DiaryListResponse response = diaryService.getDiaries(USER_ID, PLANT_ID, DATE, DATE);

        assertThat(response.diaries()).hasSize(1);
        assertThat(response.diaries().getFirst().title()).isEqualTo("첫 잎");
        assertThat(response.diaries().getFirst().thumbnailUrl()).isEqualTo("/media/t.jpg");
    }

    @Test
    void listWorksOnDaysWithoutAPhoto() {
        givenOwnedPlant();
        PlantDiary diary = PlantDiary.write(PLANT_ID, DATE, "흐린 날", "카메라가 쉬었어요.");
        when(diaryRepository.findAllByPlantIdAndDiaryDateBetweenOrderByDiaryDateDesc(
                PLANT_ID, DATE, DATE)).thenReturn(List.of(diary));
        // 로봇이 꺼져 있던 날은 사진이 없다. 일기는 그대로 나와야 한다.
        when(photoService.findDevicePhotosOfDates(PLANT_ID, List.of(DATE))).thenReturn(Map.of());

        DiaryListResponse response = diaryService.getDiaries(USER_ID, PLANT_ID, DATE, DATE);

        assertThat(response.diaries().getFirst().thumbnailUrl()).isNull();
    }

    @Test
    void detailAttachesThatDaysDevicePhoto() {
        givenOwnedPlant();
        PlantDiary diary = PlantDiary.write(PLANT_ID, DATE, "첫 잎", "오늘은 잎이 하나 났어요.");
        when(diaryRepository.findByIdAndPlantId(diary.getId(), PLANT_ID))
                .thenReturn(Optional.of(diary));
        when(photoService.findDevicePhotoOfDate(PLANT_ID, DATE))
                .thenReturn(Optional.of(photoResponse()));

        var response = diaryService.getDiary(USER_ID, PLANT_ID, diary.getId());

        assertThat(response.content()).isEqualTo("오늘은 잎이 하나 났어요.");
        assertThat(response.photo()).isNotNull();
    }

    @Test
    void reversedRangeIsRejected() {
        givenOwnedPlant();

        assertThatThrownBy(() ->
                diaryService.getDiaries(USER_ID, PLANT_ID, DATE, DATE.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryService.getDiaries(USER_ID, PLANT_ID, DATE, DATE))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PLANT_NOT_FOUND);
    }

    @Test
    void missingDiaryIsNotFound() {
        givenOwnedPlant();
        when(diaryRepository.findByIdAndPlantId("diary-missing", PLANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryService.getDiary(USER_ID, PLANT_ID, "diary-missing"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.DIARY_NOT_FOUND);
    }

    private void givenOwnedPlant() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
    }

    private PhotoResponse photoResponse() {
        return new PhotoResponse(
                "photo-id",
                DATE,
                LocalDateTime.of(2026, 7, 28, 1, 0),
                "/media/t.jpg",
                "/media/p.jpg",
                "/media/o.jpg",
                100,
                80,
                3
        );
    }
}
