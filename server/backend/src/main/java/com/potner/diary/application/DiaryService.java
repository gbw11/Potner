package com.potner.diary.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.diary.domain.PlantDiary;
import com.potner.diary.domain.PlantDiaryRepository;
import com.potner.diary.dto.DiaryDetailResponse;
import com.potner.diary.dto.DiaryListResponse;
import com.potner.diary.dto.DiarySummaryResponse;
import com.potner.photo.application.PhotoService;
import com.potner.photo.dto.PhotoResponse;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 성장 일기 저장과 조회다.
 *
 * <p>작성 API 가 없다. 일기는 사용자가 쓰는 것이 아니라 매일 정해진 시각에 LLM 이 쓴다.
 * {@link #write} 는 그 배치가 부를 자리이고, 이 작업에서는 저장과 조회까지만 만든다.
 *
 * <p>사진은 일기가 갖지 않는다. 장치가 하루 한 장 찍으므로 날짜로 찾아 붙인다.
 */
@Service
@Transactional(readOnly = true)
public class DiaryService {

    private final PlantDiaryRepository diaryRepository;
    private final PlantRepository plantRepository;
    private final PhotoService photoService;

    public DiaryService(
            PlantDiaryRepository diaryRepository,
            PlantRepository plantRepository,
            PhotoService photoService
    ) {
        this.diaryRepository = diaryRepository;
        this.plantRepository = plantRepository;
        this.photoService = photoService;
    }

    /**
     * 그날 일기를 한 편 남긴다.
     *
     * <p>소유권을 확인하지 않는다. 호출자가 사용자 요청이 아니라 배치이기 때문이다. 사용자
     * 입력이 들어오는 경로가 생기면 그때 확인을 붙여야 한다.
     *
     * <p>이미 있으면 덮어쓰지 않고 건너뛴다. 배치가 두 번 돌았을 때 멀쩡한 일기를 새 것으로
     * 갈아치우면 사용자가 읽던 내용이 소리 없이 바뀐다.
     */
    @Transactional
    public DiaryWriteResult write(String plantId, LocalDate diaryDate, String title, String content) {
        if (diaryRepository.existsByPlantIdAndDiaryDate(plantId, diaryDate)) {
            return DiaryWriteResult.SKIPPED_ALREADY_WRITTEN;
        }
        diaryRepository.save(PlantDiary.write(plantId, diaryDate, title, content));
        return DiaryWriteResult.WRITTEN;
    }

    /** 기간 내 일기 목록이다. 최신순이고 본문은 담지 않는다. */
    public DiaryListResponse getDiaries(String userId, String plantId, LocalDate from, LocalDate to) {
        requireOwnedPlant(userId, plantId);
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        List<PlantDiary> diaries = diaryRepository
                .findAllByPlantIdAndDiaryDateBetweenOrderByDiaryDateDesc(plantId, from, to);

        // 일기마다 사진을 조회하면 목록 길이만큼 쿼리가 나간다. 날짜를 모아 한 번에 받는다.
        Map<LocalDate, PhotoResponse> photos = photoService.findDevicePhotosOfDates(
                plantId,
                diaries.stream().map(PlantDiary::getDiaryDate).toList());

        return new DiaryListResponse(
                plantId,
                from,
                to,
                diaries.stream()
                        .map(diary -> DiarySummaryResponse.from(
                                diary,
                                photos.get(diary.getDiaryDate())))
                        .toList()
        );
    }

    /** 일기 상세다. 그날 장치 사진을 함께 준다. */
    public DiaryDetailResponse getDiary(String userId, String plantId, String diaryId) {
        requireOwnedPlant(userId, plantId);
        PlantDiary diary = diaryRepository.findByIdAndPlantId(diaryId, plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DIARY_NOT_FOUND));

        return DiaryDetailResponse.from(
                diary,
                photoService.findDevicePhotoOfDate(plantId, diary.getDiaryDate()).orElse(null)
        );
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
