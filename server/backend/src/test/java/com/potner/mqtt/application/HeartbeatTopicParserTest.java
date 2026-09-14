package com.potner.mqtt.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatTopicParserTest {

    private final HeartbeatTopicParser parser = new HeartbeatTopicParser();

    @Test
    void extractsRaspberryAndJetsonDeviceIds() {
        assertThat(parser.extractDeviceId(
                "potner/device/raspberry-01/status/heartbeat"
        )).contains("raspberry-01");
        assertThat(parser.extractDeviceId(
                "potner/device/jetson-01/status/heartbeat"
        )).contains("jetson-01");
    }

    @Test
    void rejectsNonHeartbeatAndMalformedTopics() {
        assertThat(parser.extractDeviceId(
                "potner/device/raspberry-01/sensor/telemetry"
        )).isEmpty();
        assertThat(parser.extractDeviceId(
                "potner/device/raspberry-01/status/heartbeat/extra"
        )).isEmpty();
        assertThat(parser.extractDeviceId(null)).isEmpty();
    }
}
