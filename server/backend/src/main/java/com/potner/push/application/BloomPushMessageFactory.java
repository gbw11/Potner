package com.potner.push.application;

import com.potner.bloom.application.BloomRecordedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 개화 기록을 사용자에게 보여줄 한국어 문구로 바꾼다.
 *
 * <p>{@link AlertPushMessageFactory} 를 고치지 않고 따로 둔다. 그쪽 switch 는 default 가 없어
 * 지표를 추가하면 빠진 조합을 컴파일 단계에서 잡는데, 개화는 지표가 아니라 그 장치를 무너뜨린다.
 *
 * <p>첫 꽃과 그 뒤의 꽃을 나누는 이유는 첫 개화가 사용자에게 훨씬 큰 사건이기 때문이다. 두 번째
 * 꽃에도 "첫 꽃" 이라고 보내면 문구를 신뢰할 수 없게 된다.
 */
@Component
public class BloomPushMessageFactory {

    /** 앱이 알림 종류를 구분하는 값이다. 이상 알림은 {@code SENSOR_ALERT} 다. */
    static final String TYPE_BLOOM = "BLOOM";

    /**
     * 앱의 포토 로그 경로다. 꽃이 폈다는 알림을 눌렀을 때 사용자가 보고 싶은 것은 그날 사진이다.
     *
     * <p>알림 목록({@code /alerts})으로 보내던 것을 옮겼다. 목록은 알림을 다시 훑는 화면이지
     * 방금 받은 소식을 확인하는 화면이 아니다.
     *
     * <p>{@link AlertPushMessageFactory#ROUTE_ALERTS} 와 상수를 공유하지 않는 이유는 그대로다 —
     * 개화만 다른 화면으로 옮길 때 이상 알림까지 따라 움직이면 안 된다.
     *
     * <p>앱이 모르는 경로를 받으면 알림 목록으로 되돌린다({@code push_notification_route.dart}).
     * 그래서 이 값을 아는 앱이 배포되기 전에 서버가 먼저 나가도 깨지지 않고, 배포 순서를 맞출
     * 필요가 없다.
     */
    static final String ROUTE_PHOTOS = "/growth/photos";

    public PushMessage create(BloomRecordedEvent event) {
        Copy copy = event.firstBloom() ? FIRST_BLOOM : ANOTHER_BLOOM;
        return new PushMessage(
                copy.title(),
                // 별명은 사용자 입력이다. 관형격 조사는 받침과 무관해 별도 처리가 필요 없다.
                copy.bodyFormat().formatted(event.plantNickname()),
                Map.of(
                        "type", TYPE_BLOOM,
                        "bloomId", event.bloomId(),
                        "plantId", event.plantId(),
                        // 포토 로그를 열 날짜다. 경로에 붙이지 않고 따로 두는 이유는 앱이 경로를
                        // 완전 일치로 검사하기 때문이다 — 쿼리를 붙이면 화이트리스트를 벗어난다.
                        "bloomDate", event.bloomDate().toString(),
                        "route", ROUTE_PHOTOS
                )
        );
    }

    private static final Copy FIRST_BLOOM = new Copy(
            "첫 꽃을 피웠어요",
            "%s의 첫 꽃이 피었어요. 사진으로 남겨보세요."
    );

    private static final Copy ANOTHER_BLOOM = new Copy(
            "새 꽃이 피었어요",
            "%s의 새 꽃이 피었어요. 사진으로 남겨보세요."
    );

    private record Copy(String title, String bodyFormat) {
    }
}
