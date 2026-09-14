package com.potner.command.application;

import com.potner.command.domain.DeviceCommandType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandResultTopicParserTest {

    private final CommandResultTopicParser parser = new CommandResultTopicParser();

    @Test
    void parsesWaterAndCaptureResultTopics() {
        assertThat(parser.parse("potner/device/raspberry-01/result/water"))
                .contains(new CommandResultTopicParser.ParsedResultTopic(
                        "raspberry-01", DeviceCommandType.WATER));
        assertThat(parser.parse("potner/device/raspberry-01/result/capture"))
                .contains(new CommandResultTopicParser.ParsedResultTopic(
                        "raspberry-01", DeviceCommandType.CAPTURE));
        assertThat(parser.parse("potner/device/raspberry-01/result/fan"))
                .contains(new CommandResultTopicParser.ParsedResultTopic(
                        "raspberry-01", DeviceCommandType.FAN));
        assertThat(parser.parse("potner/device/jetson-01/result/navigate"))
                .contains(new CommandResultTopicParser.ParsedResultTopic(
                        "jetson-01", DeviceCommandType.NAVIGATE));
    }

    @Test
    void rejectsACommandNameTheServerDoesNotKnow() {
        // 구독이 result/# 와일드카드라 장치가 새 결과를 먼저 붙이면 여기서 걸러진다.
        assertThat(parser.parse("potner/device/raspberry-01/result/dance")).isEmpty();
    }

    @Test
    void rejectsMalformedTopics() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("potner/device/raspberry-01/result")).isEmpty();
        assertThat(parser.parse("potner/device/raspberry-01/result/water/extra")).isEmpty();
        // 라즈베리의 이전 토픽이다. command/ 아래는 결과가 아니라 명령 경로라 받지 않는다.
        assertThat(parser.parse("potner/device/raspberry-01/command/water/result")).isEmpty();
        assertThat(parser.parse("potner/device//result/water")).isEmpty();
    }
}
