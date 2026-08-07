package com.potner.command.application;

import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장이 커밋된 명령을 MQTT 로 내보낸다.
 *
 * <p>{@code AFTER_COMMIT} 이 필요한 이유는 순서다. 라즈베리는 BUSY 회신을 몇 ms 만에 보낼 수
 * 있어, 커밋 전에 발행하면 결과 처리기가 아직 없는 requestId 를 찾다 버린다.
 *
 * <p>{@code @Async} 는 붙이지 않는다. 발행 핸들러가 이미 비동기({@code setAsync(true)})라
 * 브로커 응답을 기다리지 않고, 요청 스레드에서 채널에 넣는 비용은 무시할 만하다. 스레드를
 * 갈아타면 발행 순서 보장만 잃는다.
 *
 * <p>발행 실패는 {@link RobotCommandPublisher} 가 로그로 삼킨다. 행은 ISSUED 로 남고 회신이
 * 없으므로 타임아웃 스케줄러가 정리한다 — 별도의 실패 경로가 필요 없다.
 */
@Component
public class DeviceCommandPublishListener {

    private final RobotCommandPublisher commandPublisher;

    public DeviceCommandPublishListener(RobotCommandPublisher commandPublisher) {
        this.commandPublisher = commandPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandIssued(DeviceCommandIssuedEvent event) {
        Object payload = switch (event.commandType()) {
            case WATER -> new WaterCommandPayload(event.requestedMl(), event.requestId());
            case CAPTURE -> new CaptureCommandPayload(event.requestId());
            case FAN -> new FanCommandPayload(event.runSeconds(), event.requestId());
            case NAVIGATE -> new NavigateCommandPayload(
                    event.destination().name(),
                    event.poseX(),
                    event.poseY(),
                    event.poseYaw(),
                    event.requestId()
            );
            // 지도 제작은 모드 전환이라 실을 값이 없다. 무엇을 할지는 토픽 이름이 정한다.
            case MAPPING_START, MAPPING_SAVE, MAPPING_CANCEL ->
                    new MappingCommandPayload(event.requestId());
        };
        commandPublisher.publish(
                event.deviceUid(),
                new RobotCommand(event.commandType().commandName(), payload)
        );
    }
}
