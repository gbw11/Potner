package com.potner.push.application;

import com.potner.vision.application.PlantSproutedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 새싹 판정을 사용자에게 보여줄 한국어 문구로 바꾼다.
 *
 * <p>{@link BloomPushMessageFactory} 와 같은 이유로 따로 둔다 — {@link AlertPushMessageFactory}
 * 의 switch 는 default 가 없어 지표를 추가하면 빠진 조합을 컴파일 단계에서 잡는데, 새싹은
 * 지표가 아니라 그 장치를 무너뜨린다.
 *
 * <p>첫 꽃과 그 뒤의 꽃을 나누는 개화와 달리 문구가 하나다. 발아는 생애에 한 번이고, 반복
 * 알림은 냉각 기간이 막는다.
 */
@Component
public class SproutPushMessageFactory {

    /** 앱이 알림 종류를 구분하는 값이다. 개화는 {@code BLOOM}, 이상 알림은 {@code SENSOR_ALERT} 다. */
    static final String TYPE_SPROUT = "SPROUT";

    /**
     * 앱의 성장 기록 화면 경로다.
     *
     * <p>알림 목록이 아니라 이쪽으로 보낸다. 싹이 텄다는 것은 확인하고 넘기는 알림이 아니라
     * 사진과 함께 보는 기록이라, 눌렀을 때 성장 과정을 보는 화면이 열려야 한다. 생장 단계
     * 알림과 같은 방침이다.
     */
    static final String ROUTE_GROWTH = "/growth";

    public PushMessage create(PlantSproutedEvent event) {
        return new PushMessage(
                "🌱 싹이 텄어요",
                // 별명은 사용자 입력이다. 관형격 조사는 받침과 무관해 별도 처리가 필요 없다.
                "%s의 새싹이 올라왔어요. 첫 잎을 확인해 보세요.".formatted(event.plantNickname()),
                Map.of(
                        "type", TYPE_SPROUT,
                        "plantId", event.plantId(),
                        "route", ROUTE_GROWTH
                )
        );
    }
}
