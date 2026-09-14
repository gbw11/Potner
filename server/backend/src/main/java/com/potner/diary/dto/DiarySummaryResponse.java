package com.potner.diary.dto;

import com.potner.diary.domain.PlantDiary;
import com.potner.photo.dto.PhotoResponse;

import java.time.LocalDate;

/**
 * 목록 한 줄이다. 본문은 담지 않는다.
 *
 * <p>목록에서 본문을 잘라 보내면 앱이 다시 늘릴 수 없고, 전부 보내면 한 달치가 한 응답에
 * 실린다. 목록은 날짜와 제목으로 고르는 화면이고 본문은 상세에서 읽는다.
 */
public record DiarySummaryResponse(
        String diaryId,
        LocalDate diaryDate,
        String title,
        /** 그날 장치 사진의 썸네일이다. 촬영이 없던 날은 null 이다. */
        String thumbnailUrl
) {
    public static DiarySummaryResponse from(PlantDiary diary, PhotoResponse photo) {
        return new DiarySummaryResponse(
                diary.getId(),
                diary.getDiaryDate(),
                diary.getTitle(),
                photo == null ? null : photo.thumbnailUrl()
        );
    }
}
