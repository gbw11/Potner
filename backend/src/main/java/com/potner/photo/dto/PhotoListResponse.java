package com.potner.photo.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 기간 내 사진 목록이다. 오래된 것부터 정렬하므로 앱이 그대로 타임랩스로 재생할 수 있다.
 *
 * <p>{@code requestedDays} 와 {@code capturedDays} 를 함께 주는 이유는 결측일을 드러내기
 * 위함이다. 로봇이 꺼져 있거나 촬영에 실패한 날은 사진이 없어 타임랩스가 짧아진다.
 * 그것이 데이터 부족 때문인지 기간 설정 때문인지 앱이 구분할 수 있어야 한다.
 */
public record PhotoListResponse(
        String plantId,
        String zoneOffset,
        LocalDate from,
        LocalDate to,
        int requestedDays,
        int capturedDays,
        List<PhotoResponse> photos
) {
    public PhotoListResponse {
        photos = List.copyOf(photos);
    }
}
