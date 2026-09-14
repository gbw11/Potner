package com.potner.push.application;

import com.potner.alert.application.AlertOpenedEvent;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 이상 알림을 사용자에게 보여줄 한국어 문구로 바꾼다.
 *
 * <p>센서 지표 5종 × 방향 2종에 설비 알림 2종(물 부족은 LOW, 배수트레이는 HIGH 뿐)을 더해
 * 12가지다. 새 지표를 추가하면 switch 가 컴파일 단계에서 빠진 조합을 잡는다. default 를 두지
 * 않는 이유가 그것이다.
 *
 * <p>본문이 별명을 {@code %s의} 꼴로만 쓴다. 주격 조사(이/가)를 붙이면 별명의 받침에 따라
 * 문장이 어색해지는데, 관형격 조사는 받침과 무관해서 별도 처리가 필요 없다.
 */
@Component
public class AlertPushMessageFactory {

    /** 앱이 알림 종류를 구분하는 값이다. 개화 알림이 생기면 다른 값을 쓴다. */
    static final String TYPE_SENSOR_ALERT = "SENSOR_ALERT";

    /**
     * 앱의 알림 화면 경로다.
     *
     * <p>서버가 앱 화면 구조를 아는 유일한 지점이라 여기 한 곳에만 둔다. 앱이 {@code type}으로
     * 목적지를 정하게 바꾸면 이 상수와 payload 항목을 함께 지우면 된다.
     */
    static final String ROUTE_ALERTS = "/alerts";

    public PushMessage create(AlertOpenedEvent event) {
        Copy copy = copyOf(event.metricType(), event.deviation());
        return new PushMessage(
                copy.title(),
                copy.bodyFormat().formatted(event.plantNickname()),
                Map.of(
                        "type", TYPE_SENSOR_ALERT,
                        "alertId", event.alertId(),
                        "plantId", event.plantId(),
                        "route", ROUTE_ALERTS
                )
        );
    }

    private static Copy copyOf(AlertMetricType metricType, AlertDeviation deviation) {
        return switch (metricType) {
            case SOIL_MOISTURE -> switch (deviation) {
                case LOW -> new Copy(
                        "흙이 말랐어요",
                        "%s의 토양 수분이 기준보다 낮아요. 물을 주세요."
                );
                case HIGH -> new Copy(
                        "흙이 너무 젖었어요",
                        "%s의 토양 수분이 기준보다 높아요. 물 주기를 미뤄주세요."
                );
            };
            case TEMPERATURE -> switch (deviation) {
                case LOW -> new Copy(
                        "온도가 낮아요",
                        "%s의 주변 온도가 기준보다 낮아요. 따뜻한 곳으로 옮겨주세요."
                );
                case HIGH -> new Copy(
                        "온도가 높아요",
                        "%s의 주변 온도가 기준보다 높아요. 서늘한 곳으로 옮겨주세요."
                );
            };
            case HUMIDITY -> switch (deviation) {
                case LOW -> new Copy(
                        "공기가 건조해요",
                        "%s의 주변 습도가 기준보다 낮아요. 잎에 분무해 주세요."
                );
                case HIGH -> new Copy(
                        "공기가 습해요",
                        "%s의 주변 습도가 기준보다 높아요. 환기를 해주세요."
                );
            };
            // 광량 지표는 전일분을 새벽에 판정하므로 문구가 어제를 가리킨다.
            case DAILY_LIGHT -> switch (deviation) {
                case LOW -> new Copy(
                        "빛이 부족했어요",
                        "어제 %s의 누적 광량이 기준보다 적어요. 더 밝은 곳으로 옮겨주세요."
                );
                case HIGH -> new Copy(
                        "빛이 너무 많았어요",
                        "어제 %s의 누적 광량이 기준보다 많아요. 직사광선을 피해주세요."
                );
            };
            case PHOTOPERIOD -> switch (deviation) {
                case LOW -> new Copy(
                        "일조 시간이 짧았어요",
                        "어제 %s의 일조 시간이 기준보다 짧아요. 빛을 더 오래 보여주세요."
                );
                case HIGH -> new Copy(
                        "일조 시간이 길었어요",
                        "어제 %s의 일조 시간이 기준보다 길어요. 밤에는 빛을 차단해 주세요."
                );
            };
            case STATION_WATER_LOW -> switch (deviation) {
                case LOW -> new Copy(
                        "스테이션 물이 부족해요",
                        "%s의 원활한 급수를 위해 스테이션에 물을 보충해 주세요."
                );
                // 물 부족 보고는 불리언이라 LOW 로만 열린다. 여기 도달하면 여는 쪽이 잘못됐다.
                case HIGH -> throw new IllegalStateException(
                        "STATION_WATER_LOW alert cannot have HIGH deviation");
            };
            case DRAINAGE_TRAY -> switch (deviation) {
                // 트레이는 넘칠 때만 문제다. 비어 있는 것은 알릴 일이 아니다.
                case HIGH -> new Copy(
                        "배수트레이를 비워주세요",
                        "%s의 배수트레이에 물이 찼어요. 넘치지 않도록 비워 주세요."
                );
                case LOW -> throw new IllegalStateException(
                        "DRAINAGE_TRAY alert cannot have LOW deviation");
            };
        };
    }

    private record Copy(String title, String bodyFormat) {
    }
}
