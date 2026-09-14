package com.potner.bloom.dto;

import com.potner.bloom.domain.BloomSource;
import com.potner.bloom.domain.PlantBloom;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 개화 기록 한 건이다.
 *
 * <p>{@code bloomDate} 만 서비스 타임존 기준 날짜고 {@code createdAt} 은 UTC 다. 알림 목록과
 * 같은 규칙이라 앱이 시각 처리를 나눠 하지 않아도 된다.
 */
public record BloomResponse(
        String bloomId,
        String plantId,
        String plantName,
        LocalDate bloomDate,
        String note,
        BloomSource source,
        boolean read,
        LocalDateTime createdAt
) {

    public static BloomResponse of(PlantBloom bloom, String plantName) {
        return new BloomResponse(
                bloom.getId(),
                bloom.getPlantId(),
                plantName,
                bloom.getBloomDate(),
                bloom.getNote(),
                bloom.getSource(),
                bloom.isRead(),
                bloom.getCreatedAt()
        );
    }
}
