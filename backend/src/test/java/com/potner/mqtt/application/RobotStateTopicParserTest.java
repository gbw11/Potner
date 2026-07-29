package com.potner.mqtt.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotStateTopicParserTest {

    private final RobotStateTopicParser parser = new RobotStateTopicParser();

    @Test
    void deviceIdIsTakenFromTheTopic() {
        assertThat(parser.extractDeviceId("potner/device/jetson-01/status/state"))
                .contains("jetson-01");
    }

    @Test
    void heartbeatTopicIsNotAStateTopic() {
        // 두 토픽이 같은 status/ 아래에 있다. 구독기가 접미사로 갈라 보내지만 파서도 막아야
        // 한쪽 처리기가 다른 쪽 페이로드를 받지 않는다.
        assertThat(parser.extractDeviceId("potner/device/jetson-01/status/heartbeat")).isEmpty();
    }

    @Test
    void malformedTopicsAreRejected() {
        assertThat(parser.extractDeviceId(null)).isEmpty();
        assertThat(parser.extractDeviceId("")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device//status/state")).isEmpty();
        // 장치 자리에 슬래시가 더 있으면 다른 장치의 토픽을 흉내낼 수 있다.
        assertThat(parser.extractDeviceId("potner/device/a/b/status/state")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device/jetson-01/status/state/extra")).isEmpty();
        assertThat(parser.extractDeviceId("prefix/potner/device/jetson-01/status/state")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device/" + "a".repeat(101) + "/status/state"))
                .isEmpty();
    }
}
