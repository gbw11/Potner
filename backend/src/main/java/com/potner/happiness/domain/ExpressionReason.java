package com.potner.happiness.domain;

/**
 * 그 표정이 나온 이유다.
 *
 * <p>서버가 문구를 만들지 않는다. 알림 API 가 {@code metricType} 과 {@code deviation} 을 주고
 * 앱이 문장을 조립하는 것과 같은 방침이다. 로봇이 표정 옆에 말풍선을 띄우고 싶다면 이 값으로
 * 자기 문구를 고르면 된다.
 *
 * <p>{@link #WATERING} 과 {@link #GREETING} 은 로봇 상태를 받기 시작한 뒤에 나온다. 지금은
 * 만들어지지 않지만 값은 미리 둔다.
 */
public enum ExpressionReason {

    /** 급수 또는 송풍을 받는 중이다. 로봇 상태가 붙은 뒤에 나온다. */
    WATERING,

    /** 사용자를 반기는 중이다. 로봇 상태가 붙은 뒤에 나온다. */
    GREETING,

    /** 오늘 개화 기록이 있다. 하루 내내 유지된다. */
    BLOOMED,

    /** 목표 조도에 견줄 만큼 밝은 곳에 있다. */
    SUNLIGHT,

    /** 온도가 기준을 벗어났다. */
    TEMPERATURE,

    /** 습도가 기준을 벗어났다. */
    HUMIDITY,

    /** 특별한 일이 없다. 신선한 측정값이 없을 때도 이 값이다. */
    NONE
}
