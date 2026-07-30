package com.potner.command.application;

import com.potner.command.domain.DeviceCommandType;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결과 회신 토픽에서 장치 식별자와 명령 종류를 꺼낸다.
 *
 * <p>{@code potner/device/{uid}/result/{command}} 형식이다. 명령 종류가 서버가 모르는 이름이면
 * 전체를 거부한다 — 구독이 와일드카드({@code result/#})라 장치가 새 결과를 먼저 붙이면 여기서
 * 걸러지고, 로그로 어긋남이 드러난다.
 */
@Component
public class CommandResultTopicParser {

    private static final Pattern RESULT_TOPIC = Pattern.compile(
            "^potner/device/([^/]{1,100})/result/([^/]+)$"
    );

    public Optional<ParsedResultTopic> parse(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        Matcher matcher = RESULT_TOPIC.matcher(topic);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return commandType(matcher.group(2))
                .map(type -> new ParsedResultTopic(matcher.group(1), type));
    }

    private Optional<DeviceCommandType> commandType(String commandName) {
        for (DeviceCommandType type : DeviceCommandType.values()) {
            if (type.commandName().equals(commandName)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public record ParsedResultTopic(String deviceUid, DeviceCommandType commandType) {
    }
}
