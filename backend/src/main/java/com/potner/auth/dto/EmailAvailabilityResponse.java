package com.potner.auth.dto;

/**
 * 이메일 사용 가능 여부다.
 *
 * @param email     정규화된(trim + 소문자) 이메일. 요청 값을 그대로 돌려주지 않는 이유는 가입에
 *                  실제로 저장되는 형태를 앱이 보여줄 수 있어야 하기 때문이다
 * @param available {@code true} 면 가입에 쓸 수 있다. <strong>확인과 가입 사이에 다른 사용자가
 *                  같은 주소로 가입할 수 있으므로 보장이 아니다.</strong> 최종 판정은 가입
 *                  API 의 409 {@code EMAIL_ALREADY_EXISTS} 다
 */
public record EmailAvailabilityResponse(
        String email,
        boolean available
) {
}
