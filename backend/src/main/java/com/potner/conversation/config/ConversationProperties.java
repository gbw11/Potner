package com.potner.conversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 대화 조회 페이지 크기 설정이다.
 *
 * <p>상한을 두는 이유는 발화가 무한히 쌓이는 데이터라 한 번에 다 달라고 하면 응답이 수십 MB 가
 * 될 수 있기 때문이다. 요청이 상한을 넘으면 거절하지 않고 상한으로 줄인다 —
 * {@code potner.alert.max-page-size} 와 같은 방침이다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.conversation")
public record ConversationProperties(

        /** 요청이 개수를 주지 않을 때 쓴다. */
        @Positive int defaultPageSize,

        /** 요청이 이보다 크게 달라고 하면 이 값으로 줄인다. */
        @Positive int maxPageSize,

        /**
         * 젯슨이 대화를 복원할 때 받는 발화 수다.
         *
         * <p>앱 페이지 크기와 나눠 두는 이유는 쓰는 목적이 다르기 때문이다. 앱은 사람이 스크롤해
         * 읽는 양이고, 이쪽은 LLM 프롬프트에 들어갈 양이다. 젯슨의 {@code max_history_turns}(기본
         * 10턴)보다 넉넉하면 되고, 크게 잡으면 토큰 비용만 는다.
         */
        @Positive int deviceRestoreSize
) {

    @AssertTrue(message = "potner.conversation.default-page-size must not exceed max-page-size")
    public boolean isDefaultWithinMax() {
        return defaultPageSize <= maxPageSize;
    }

    @AssertTrue(message = "potner.conversation.device-restore-size must not exceed max-page-size")
    public boolean isDeviceRestoreWithinMax() {
        return deviceRestoreSize <= maxPageSize;
    }
}
