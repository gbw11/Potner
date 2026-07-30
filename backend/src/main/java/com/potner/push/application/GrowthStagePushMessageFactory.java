package com.potner.push.application;

import com.potner.vision.application.GrowthStageAdvancedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 생장 단계가 올라간 것을 사용자에게 보여줄 한국어 문구로 바꾼다.
 *
 * <p>{@link AlertPushMessageFactory} 와 {@link BloomPushMessageFactory} 를 고치지 않고 따로 둔다.
 * 이상 알림 쪽 switch 는 default 가 없어 지표를 추가하면 빠진 조합을 컴파일 단계에서 잡는데,
 * 생장 단계는 지표가 아니라 그 장치를 무너뜨린다. 개화를 따로 둔 것과 같은 이유다.
 *
 * <p>단계 이름을 문구에 그대로 넣는다. 판정 단계별로 문구를 따로 두지 않는 이유는 참조 데이터의
 * 단계 이름(발아기·영양생장기·개화기)이 이미 사용자에게 보여줄 한국어이고, 모델이 클래스를 늘리면
 * 여기서 빠진 조합이 생기기 때문이다.
 */
@Component
public class GrowthStagePushMessageFactory {

    /** 앱이 알림 종류를 구분하는 값이다. 이상 알림은 {@code SENSOR_ALERT}, 개화는 {@code BLOOM} 이다. */
    static final String TYPE_GROWTH_STAGE = "GROWTH_STAGE";

    /**
     * 앱의 성장 기록 화면 경로다.
     *
     * <p>알림 화면이 아니라 이쪽으로 보낸다. 단계 변화는 확인하고 넘기는 알림이 아니라 사진과
     * 함께 보는 기록이라, 사용자가 눌렀을 때 성장 과정을 보는 화면이 열려야 한다.
     */
    static final String ROUTE_GROWTH = "/growth";

    public PushMessage create(GrowthStageAdvancedEvent event) {
        return new PushMessage(
                "%s(으)로 자랐어요".formatted(event.lifeStageName()),
                // 별명은 사용자 입력이다. 관형격 조사는 받침과 무관해 별도 처리가 필요 없다.
                "%s이(가) %s에 들어섰어요. 사진으로 확인해 보세요.".formatted(
                        event.plantNickname(),
                        event.lifeStageName()
                ),
                Map.of(
                        "type", TYPE_GROWTH_STAGE,
                        "plantId", event.plantId(),
                        "stage", event.detectedStage().name(),
                        "route", ROUTE_GROWTH
                )
        );
    }
}
