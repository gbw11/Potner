package com.potner.mqtt.application;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code potner/device/{deviceUid}/status/battery} 에서 장치 식별자를 뽑는다.
 *
 * <p>토픽에서 뽑은 값이 신뢰의 근거다. ACL 이 장치를 자기 토픽에만 묶어 두므로 이 세그먼트는
 * 장치가 위조할 수 없고, 페이로드의 {@code deviceId} 는 이 값과 같은지 확인하는 대상일 뿐이다.
 */
@Component
public class BatteryTopicParser {

    private static final Pattern BATTERY_TOPIC = Pattern.compile(
            "^potner/device/([^/]{1,100})/status/battery$"
    );

    public Optional<String> extractDeviceId(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        Matcher matcher = BATTERY_TOPIC.matcher(topic);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
