package com.potner.arrival.application;

import com.potner.arrival.domain.ArrivalEventType;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ArrivalResultTopicParser {

    private static final Pattern RESULT_TOPIC = Pattern.compile(
            "^potner/device/([^/]{1,100})/result/([^/]+)$"
    );

    public Optional<ParsedArrivalResultTopic> parse(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        Matcher matcher = RESULT_TOPIC.matcher(topic);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        ArrivalEventType eventType = ArrivalEventType.fromCommandName(matcher.group(2));
        return eventType == null
                ? Optional.empty()
                : Optional.of(new ParsedArrivalResultTopic(matcher.group(1), eventType));
    }

    public record ParsedArrivalResultTopic(String deviceUid, ArrivalEventType eventType) {
    }
}
