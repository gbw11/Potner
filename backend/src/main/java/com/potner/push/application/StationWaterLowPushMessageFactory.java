package com.potner.push.application;

import com.potner.location.application.StationWaterLowEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 스테이션 물 부족을 사용자에게 보여줄 한국어 문구로 바꾼다.
 *
 * <p>{@link AlertPushMessageFactory} 를 고치지 않고 따로 둔다. 그쪽 switch 는 default 가 없어
 * 지표를 추가하면 빠진 조합을 컴파일 단계에서 잡는데, 물 부족은 식물 지표가 아니라 스테이션
 * 상태라 그 장치를 무너뜨린다. 개화·생장 단계를 따로 둔 것과 같은 이유다.
 */
@Component
public class StationWaterLowPushMessageFactory {

    /** 앱이 알림 종류를 구분하는 값이다. */
    static final String TYPE_STATION_WATER_LOW = "STATION_WATER_LOW";

    /**
     * 앱의 알림 화면 경로다. 디자인의 "이상 알림" 섹션에 스테이션 물 부족이 들어 있다.
     */
    static final String ROUTE_ALERTS = "/alerts";

    public PushMessage create(StationWaterLowEvent event) {
        return new PushMessage(
                "스테이션 물이 부족해요",
                "원활한 급수를 위해 스테이션에 물을 보충해 주세요.",
                Map.of(
                        "type", TYPE_STATION_WATER_LOW,
                        "robotId", event.robotId(),
                        "route", ROUTE_ALERTS
                )
        );
    }
}
