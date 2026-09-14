package com.potner.happiness.application;

import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;

/**
 * 기준을 벗어난 지표 하나다.
 *
 * <p>등급만 주면 앱이 "무엇을" 확인해야 하는지 알 수 없다. 서버가 문구를 만들어 주더라도 이
 * 목록을 함께 내려야, 앱이 나중에 자체 문구로 바꾸거나 지표별 아이콘을 붙일 수 있다.
 *
 * @param status {@code LOW} 또는 {@code HIGH} 만 담긴다. 정상 지표는 목록에 들어가지 않는다.
 */
public record AbnormalMetric(SensorType sensorType, SensorStatus status) {

    /** 사용자에게 보여줄 지표 이름이다. 문구 조립에 쓰인다. */
    public String label() {
        return switch (sensorType) {
            case TEMPERATURE -> "온도";
            case HUMIDITY -> "습도";
            case SOIL_MOISTURE -> "토양 수분";
            // 조도는 순간값으로 판정하지 않으므로 여기 들어오지 않는다. switch 를 닫아 두면
            // 센서를 추가할 때 컴파일 단계에서 이 자리를 다시 보게 된다.
            case ILLUMINANCE -> "조도";
        };
    }
}
