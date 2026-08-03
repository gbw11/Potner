package com.potner.push.application;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 분갈이 시기 알림을 사용자에게 보여줄 한국어 문구로 바꾼다.
 *
 * <p>{@link AlertPushMessageFactory} 를 고치지 않고 따로 둔다. 그쪽 switch 는 default 가 없어
 * 지표를 추가하면 빠진 조합을 컴파일 단계에서 잡는데, 분갈이는 센서 지표가 아니라 그 장치를
 * 무너뜨린다. 개화({@link BloomPushMessageFactory})와 생장 단계를 따로 둔 것과 같은 이유다.
 *
 * <p>{@code alert} 테이블에 기록하지 않는다. 그 테이블은 센서 판정으로 열리고 해소되는
 * 것이라 "지금 이상하다" 를 담는데, 분갈이는 해소되는 상태가 아니라 한 번 알리면 끝나는
 * 안내다. 기록하려면 지표 enum 과 DB CHECK 를 함께 늘려야 하고 그만한 값이 없다.
 */
@Component
public class RepottingPushMessageFactory {

    /** 앱이 알림 종류를 구분하는 값이다. 이상 알림은 {@code SENSOR_ALERT}, 개화는 {@code BLOOM} 이다. */
    static final String TYPE_REPOTTING = "REPOTTING";

    /**
     * 앱의 분갈이 방법 화면 경로다.
     *
     * <p>알림 목록이 아니라 이쪽으로 보낸다. 분갈이는 확인하고 넘기는 알림이 아니라 지금
     * 무엇을 어떻게 해야 하는지가 필요한 안내라, 눌렀을 때 방법이 바로 보여야 한다.
     */
    static final String ROUTE_REPOTTING = "/repotting";

    public PushMessage create(String plantId, String plantNickname) {
        return new PushMessage(
                "분갈이 할 때가 됐어요",
                // 별명은 사용자 입력이다. 관형격 조사는 받침과 무관해 별도 처리가 필요 없다.
                "%s의 뿌리가 화분을 채웠을 시기예요. 분갈이 방법을 확인해 보세요.".formatted(plantNickname),
                Map.of(
                        "type", TYPE_REPOTTING,
                        "plantId", plantId,
                        "route", ROUTE_REPOTTING
                )
        );
    }
}
