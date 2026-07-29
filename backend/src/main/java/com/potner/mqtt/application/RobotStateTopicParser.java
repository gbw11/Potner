package com.potner.mqtt.application;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code potner/device/{deviceUid}/status/state} 에서 장치 식별자를 뽑는다.
 *
 * <p>형태를 정규식으로 고정한다. 토픽에서 뽑은 값이 신뢰의 근거이고, 페이로드의 {@code deviceId}
 * 는 이 값과 같은지 확인하는 대상일 뿐이다. ACL 이 장치를 자기 토픽에만 쓰도록 묶어 두므로,
 * 토픽 세그먼트는 장치가 위조할 수 없다.
 */
@Component
public class RobotStateTopicParser {

    private static final Pattern STATE_TOPIC = Pattern.compile(
            "^potner/device/([^/]{1,100})/status/state$"
    );

    public Optional<String> extractDeviceId(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        Matcher matcher = STATE_TOPIC.matcher(topic);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
