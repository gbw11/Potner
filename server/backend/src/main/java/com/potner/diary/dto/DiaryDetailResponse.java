package com.potner.diary.dto;

import com.potner.diary.domain.PlantDiary;
import com.potner.photo.dto.PhotoResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일기 상세다.
 *
 * <p>상태 리포트는 여기 담지 않는다. 일기가 없는 날에도 점수는 있어야 하고 홈 화면도 오늘
 * 상태를 쓰므로 별도 엔드포인트로 둔다. 일기에 묶으면 일기를 안 쓴 날의 점수를 볼 수 없다.
 */
public record DiaryDetailResponse(
        String diaryId,
        LocalDate diaryDate,
        String title,
        String content,
        /** 그날 장치가 찍은 사진이다. 촬영이 없던 날은 null 이다. */
        PhotoResponse photo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static DiaryDetailResponse from(PlantDiary diary, PhotoResponse photo) {
        return new DiaryDetailResponse(
                diary.getId(),
                diary.getDiaryDate(),
                diary.getTitle(),
                diary.getContent(),
                photo,
                diary.getCreatedAt(),
                diary.getUpdatedAt()
        );
    }
}
