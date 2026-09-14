package com.potner.mqtt.application;

/**
 * 서버가 로봇에 명령을 보내는 경로다.
 *
 * <p>구현을 갈아끼우는 이유가 {@code PushSender} 와 같다. 브로커가 없는 로컬 개발이나 CI 임시
 * 컨테이너에서 발행기 초기화가 실패하면 애플리케이션이 뜨지 않고 Health Check 가 실패해 배포가
 * 막힌다. 자격증명이나 브로커가 없을 때는 기능만 빠지고 나머지는 그대로 돌아야 한다.
 */
public interface RobotCommandPublisher {

    /**
     * 지정한 장치에 명령을 보낸다.
     *
     * <p><strong>예외를 던지지 않는다.</strong> 호출자가 주기 스케줄러나 커밋 이후 리스너라서,
     * 브로커가 잠깐 끊긴 것으로 그쪽 흐름이 멈추면 안 된다. 실패는 로그로 남는다.
     */
    void publish(String deviceUid, RobotCommand command);
}
