package com.potner.diary.dto;

import java.time.LocalDate;
import java.util.List;

public record DiaryListResponse(
        String plantId,
        LocalDate from,
        LocalDate to,
        List<DiarySummaryResponse> diaries
) {
    public DiaryListResponse {
        diaries = List.copyOf(diaries);
    }
}
