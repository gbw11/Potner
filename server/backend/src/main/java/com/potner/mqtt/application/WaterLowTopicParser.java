package com.potner.mqtt.application;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WaterLowTopicParser {

    private static final Pattern WATER_LOW_TOPIC = Pattern.compile(
            "^potner/device/([^/]{1,100})/status/water-low$"
    );

    public Optional<String> extractDeviceId(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        Matcher matcher = WATER_LOW_TOPIC.matcher(topic);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
