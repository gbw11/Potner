package com.potner.arrival.application;

import com.potner.arrival.domain.ArrivalEventType;
import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ArrivalCommandPublishListener {

    private final RobotCommandPublisher commandPublisher;

    public ArrivalCommandPublishListener(RobotCommandPublisher commandPublisher) {
        this.commandPublisher = commandPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandIssued(ArrivalCommandIssuedEvent event) {
        Object payload = event.eventType() == ArrivalEventType.APPROACH
                ? new WelcomeStartPayload(
                        event.eventId(),
                        event.visitId(),
                        event.eventId(),
                        "GREETING",
                        event.greetingX(),
                        event.greetingY(),
                        event.greetingYaw(),
                        "HOME",
                        event.homeX(),
                        event.homeY(),
                        event.homeYaw(),
                        event.welcomeWaitSeconds(),
                        event.totalTimeoutSeconds(),
                        event.publishedAt()
                )
                : new WelcomeCancelPayload(
                        event.eventId(),
                        event.visitId(),
                        event.eventId(),
                        "HOME",
                        event.homeX(),
                        event.homeY(),
                        event.homeYaw(),
                        event.publishedAt()
                );
        commandPublisher.publish(
                event.deviceUid(),
                new RobotCommand(event.eventType().commandName(), payload)
        );
    }
}
