package com.potner.mqtt.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브로커가 없을 때 쓰는 발행기다. 보내지 않고 남긴다.
 *
 * <p>{@code LoggingPushSender} 와 같은 자리다. 로컬 개발과 CI 임시 컨테이너에는 브로커가 없다.
 * 그래도 명령이 나갈 자리에서 무엇이 나갔을지는 확인할 수 있어야 판정 로직을 검증할 수 있다.
 */
public class LoggingRobotCommandPublisher implements RobotCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingRobotCommandPublisher.class);

    @Override
    public void publish(String deviceUid, RobotCommand command) {
        log.info(
                "Robot command not sent because MQTT is disabled: deviceUid={}, command={}, payload={}",
                deviceUid,
                command.name(),
                command.payload()
        );
    }
}
