package com.potner.photo.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 사진 한 장이다. 세 URL 은 용도가 다르다.
 *
 * <ul>
 *   <li>{@code thumbnailUrl} — 포토 로그 그리드</li>
 *   <li>{@code playbackUrl} — 타임랩스 재생. 원본은 30장이면 수십 MB 라 모바일에서 끊긴다</li>
 *   <li>{@code originalUrl} — 포토 상세, 성장 비교</li>
 * </ul>
 *
 * <p>이 URL 에는 인증이 걸리지 않는다. 경로에 UUID 두 개가 들어가 추측할 수 없지만,
 * URL 이 유출되면 접근이 가능하므로 외부에 공유할 때 주의해야 한다.
 */
public record PhotoResponse(
        String photoId,
        LocalDate photoDate,
        LocalDateTime capturedAt,
        String thumbnailUrl,
        String playbackUrl,
        String originalUrl,
        int width,
        int height,
        long byteSize
) {
}
