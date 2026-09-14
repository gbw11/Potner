package com.potner.happiness.application;

import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.dto.ExpressionCommandPayload;
import com.potner.mqtt.application.RobotCommand;
import com.potner.mqtt.application.RobotCommandPublisher;
import com.potner.plant.domain.PlantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 배정된 로봇들에게 현재 표정을 주기적으로 보낸다.
 *
 * <p>변화를 감지해서 보내지 않는다. 매번 계산해 그대로 발행하면 이벤트 배선이 필요 없고, 로봇이
 * 재시작해도 한 주기 안에 표정을 되찾는다. 지금 판정 근거(온·습도, 개화, 광량)가 분 단위로
 * 바뀌므로 주기 발행으로 충분하다.
 *
 * <p>급수가 붙으면 "물 마시기 시작"은 즉시 바뀌어야 하므로 그때 이벤트 발행을 더한다. 주기
 * 발행은 그대로 두고 안전망으로 쓴다.
 */
@Component
public class ExpressionPublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpressionPublishScheduler.class);

    private final PlantDeviceAssignmentRepository assignmentRepository;
    private final HappinessService happinessService;
    private final RobotCommandPublisher commandPublisher;
    private final HappinessProperties properties;

    public ExpressionPublishScheduler(
            PlantDeviceAssignmentRepository assignmentRepository,
            HappinessService happinessService,
            RobotCommandPublisher commandPublisher,
            HappinessProperties properties
    ) {
        this.assignmentRepository = assignmentRepository;
        this.happinessService = happinessService;
        this.commandPublisher = commandPublisher;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${potner.happiness.publish-interval-seconds}",
            timeUnit = TimeUnit.SECONDS
    )
    public void publishExpressions() {
        if (!properties.publishEnabled()) {
            return;
        }
        publishOnce();
    }

    /** 테스트와 스케줄러가 함께 쓴다. 발행한 건수를 돌려준다. */
    public int publishOnce() {
        List<AssignedDeviceView> targets = assignmentRepository.findActiveAssignedDevices(
                properties.displayDeviceType(),
                PlantStatus.DELETED
        );
        if (targets.isEmpty()) {
            // 보낼 대상이 없으면 조용히 도는데, 그러면 "잘 돌고 있다" 와 "아무것도 안 나간다" 를
            // 로그로 구별할 수 없다. 배정된 장치가 없는 것은 정상 상태라 warn 은 과하지만,
            // 표정이 안 보이는 원인이 여기임을 알 수 있어야 한다. 장치를 배정하면 사라진다.
            log.info(
                    "Expression publish has no target: no active plant with a {} device",
                    properties.displayDeviceType()
            );
            return 0;
        }

        int published = 0;
        for (AssignedDeviceView target : targets) {
            try {
                PlantExpressionState state = happinessService.judge(target.getPlantId());
                commandPublisher.publish(
                        target.getDeviceUid(),
                        RobotCommand.expression(new ExpressionCommandPayload(
                                target.getPlantId(),
                                state.expression(),
                                state.reason()
                        ))
                );
                published++;
            } catch (RuntimeException exception) {
                // 한 식물의 판정 실패로 나머지 로봇의 표정이 멈추면 안 된다.
                log.warn(
                        "Expression publish failed: plantId={}, deviceUid={}",
                        target.getPlantId(),
                        target.getDeviceUid(),
                        exception
                );
            }
        }
        // 요약을 따로 남기지 않는다. 발행기가 건마다 info 로 남기므로 겹친다. 대상 수가 필요한
        // 경우는 위 '대상 없음' 뿐이고 그때는 이미 남겼다.
        return published;
    }
}
