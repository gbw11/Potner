package com.potner.push.dto;

import com.potner.push.domain.PushPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 기기 등록 요청이다. 설치 ID는 경로에서 받으므로 본문에 두지 않는다.
 *
 * <p>{@code platform}에 알 수 없는 값이 오면 본문 역직렬화 단계에서 400이 된다.
 */
public record RegisterFcmTokenRequest(

        @NotBlank
        @Size(max = 512)
        String token,

        @NotNull
        PushPlatform platform
) {
}
