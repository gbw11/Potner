package com.potner.mqtt.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensorTelemetryTopicParserTest {

    private final SensorTelemetryTopicParser parser = new SensorTelemetryTopicParser();

    @Test
    void extractsDeviceIdFromTelemetryTopic() {
        assertThat(parser.extractDeviceId("potner/device/raspberry-01/sensor/telemetry"))
                .contains("raspberry-01");
    }

    @Test
    void rejectsInvalidOrAdditionalTopicPaths() {
        assertThat(parser.extractDeviceId("potner/device/raspberry-01/sensor/temperature")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device/raspberry-01/sensor/telemetry/extra")).isEmpty();
        assertThat(parser.extractDeviceId("potner/device//sensor/telemetry")).isEmpty();
        assertThat(parser.extractDeviceId(null)).isEmpty();
    }
}
