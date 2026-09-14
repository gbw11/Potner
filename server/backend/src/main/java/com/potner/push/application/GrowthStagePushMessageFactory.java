package com.potner.push.application;

import com.potner.vision.application.GrowthStageAdvancedEvent;
import com.potner.vision.domain.DetectedGrowthStage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 생장 단계가 올라간 것을 사용자에게 보여줄 한국어 문구로 바꾼다.
 *
 * <p>{@link AlertPushMessageFactory} 와 {@link BloomPushMessageFactory} 를 고치지 않고 따로 둔다.
 * 이상 알림 쪽 switch 는 default 가 없어 지표를 추가하면 빠진 조합을 컴파일 단계에서 잡는데,
 * 생장 단계는 지표가 아니라 그 장치를 무너뜨린다. 개화를 따로 둔 것과 같은 이유다.
 *
 * <p><strong>단계 이름을 그대로 쓰지 않는다.</strong> "영양생장기(으)로 자랐어요" 는 참조 데이터의
 * 용어이지 식물을 기르는 사람이 쓰는 말이 아니다. 사용자가 실제로 본 변화 — 잎이 나왔다 — 를
 * 그대로 적는다.
 *
 * <p>모델이 판정하는 세 단계마다 문구를 둔다. switch 에 default 를 두지 않아 모델이 클래스를
 * 늘리면 빠진 조합이 컴파일 단계에서 잡힌다. {@link AlertPushMessageFactory} 와 같은 장치다.
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
        Copy copy = copyOf(event.detectedStage());
        return new PushMessage(
                copy.title(),
                // 별명은 사용자 입력이다. 관형격 조사는 받침과 무관해 별도 처리가 필요 없다.
                copy.bodyFormat().formatted(event.plantNickname()),
                Map.of(
                        "type", TYPE_GROWTH_STAGE,
                        "plantId", event.plantId(),
                        "stage", event.detectedStage().name(),
                        "route", ROUTE_GROWTH
                )
        );
    }

    private static Copy copyOf(DetectedGrowthStage stage) {
        return switch (stage) {
            // 발아기는 생장 단계의 맨 아래라 승급으로 도달할 수 없다. 그래도 문구를 두는 이유는
            // switch 의 완전성을 유지하기 위해서다 — 단계 순서가 바뀌면 이 값이 쓰이게 된다.
            // 실제 새싹 알림은 승급과 무관한 SproutPushMessageFactory 가 담당한다.
            case GERMINATION -> new Copy(
                    "🌱 싹이 텄어요",
                    "%s의 새싹이 올라왔어요. 첫 잎을 확인해 보세요."
            );
            case VEGETATIVE -> new Copy(
                    "🌿 잎이 나왔어요",
                    "%s의 잎이 자랐어요. 사진으로 확인해 보세요."
            );
            // GrowthStagePushListener 가 개화 승급은 보내지 않으므로 실제로는 쓰이지 않는다.
            // 꽃 소식은 개화 기록이 맡는다 — 같은 사진으로 알림이 두 번 가면 안 된다.
            case FLOWERING -> new Copy(
                    "🌸 꽃이 피었어요",
                    "%s의 꽃이 피었어요. 사진으로 남겨보세요."
            );
        };
    }

    private record Copy(String title, String bodyFormat) {
    }
}
