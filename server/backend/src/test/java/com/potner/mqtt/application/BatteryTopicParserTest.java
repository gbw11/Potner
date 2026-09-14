package com.potner.mqtt.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatteryTopicParserTest {

    private final BatteryTopicParser parser = new BatteryTopicParser();

    @Test
    void deviceIdIsTakenFromTheTopic() {
        assertThat(parser.extractDeviceId("potner/device/jetson-01/status/battery"))
                .contains("jetson-01");
    }

    @Test
    void otherStatusTopicsAreNotBatteryTopics() {
        // status/ 아래에 heartbeat, state, battery 가 함께 있다. 구독기가 접미사로 갈라
        // 보내지만 파서도 막아야 한쪽 처리기가 다른 쪽 페이로드를 받지 않는다.
        assertThat(parser.extractDeviceId("potner/device/jetson-01/status/heartbeat")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device/jetson-01/status/state")).isEmpty();
    }

    @Test
    void malformedTopicsAreRejected() {
        assertThat(parser.extractDeviceId(null)).isEmpty();
        assertThat(parser.extractDeviceId("")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device//status/battery")).isEmpty();
        // 장치 자리에 슬래시가 더 있으면 다른 장치의 토픽을 흉내낼 수 있다.
        assertThat(parser.extractDeviceId("potner/device/a/b/status/battery")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device/jetson-01/status/battery/extra")).isEmpty();
        assertThat(parser.extractDeviceId("prefix/potner/device/jetson-01/status/battery"))
                .isEmpty();
        assertThat(parser.extractDeviceId("potner/device/" + "a".repeat(101) + "/status/battery"))
                .isEmpty();
    }
}
