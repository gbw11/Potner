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
     * 앱의 알림 화면 경로다. 이상 알림과 같은 화면으로 보낸다. 개화 탭이 그 화면 안에 있다.
     *
     * <p>{@link AlertPushMessageFactory#ROUTE_ALERTS} 와 값이 같지만 상수를 공유하지 않는다.
     * 개화만 다른 화면으로 옮길 때 이상 알림까지 따라 움직이면 안 된다.
     */
    static final String ROUTE_ALERTS = "/alerts";

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
                        "route", ROUTE_ALERTS
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
