package com.potner.arrival.application;

import com.potner.arrival.domain.ArrivalEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArrivalResultTopicParserTest {

    private final ArrivalResultTopicParser parser = new ArrivalResultTopicParser();

    @Test
    void parsesWelcomeStartAndCancelTopics() {
        assertThat(parser.parse("potner/device/jetson-01/result/welcome_start"))
                .get()
                .extracting(
                        ArrivalResultTopicParser.ParsedArrivalResultTopic::deviceUid,
                        ArrivalResultTopicParser.ParsedArrivalResultTopic::eventType
                )
                .containsExactly("jetson-01", ArrivalEventType.APPROACH);

        assertThat(parser.parse("potner/device/jetson-01/result/welcome_cancel"))
                .get()
                .extracting(
                        ArrivalResultTopicParser.ParsedArrivalResultTopic::deviceUid,
                        ArrivalResultTopicParser.ParsedArrivalResultTopic::eventType
                )
                .containsExactly("jetson-01", ArrivalEventType.CANCEL);
    }

    @Test
    void rejectsOtherCommandsAndMalformedTopics() {
        assertThat(parser.parse("potner/device/jetson-01/result/navigate")).isEmpty();
        assertThat(parser.parse("potner/device/jetson-01/result/welcome_start/extra")).isEmpty();
    }
}
