package com.potner.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 가입 전 이메일 사용 가능 여부 확인 요청이다.
 *
 * <p>제약을 {@link SignupRequest#email()} 과 똑같이 둔다. 여기서 통과한 값이 가입에서 형식
 * 오류로 막히면 사용자는 "확인했는데 왜" 가 된다.
 *
 * <p>GET 쿼리스트링이 아니라 본문으로 받는다. 이메일이 nginx 접근 로그에 남으면 안 된다.
 */
public record EmailAvailabilityRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email
) {
}
