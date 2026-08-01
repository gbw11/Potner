package com.potner.alert.application;

import com.potner.command.application.DeviceCommandCompletedEvent;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 급수가 끝나면 배수트레이 상태를 다시 본다.
 *
 * <p>{@code AFTER_COMMIT} 이 필요한 이유는 누적 합계가 방금 저장된 {@code dispensedMl} 을
 * 포함해야 하기 때문이다. 커밋 전에 세면 이번 급수가 빠져 알림이 한 주기 늦는다.
 *
 * <p>{@code initiator} 를 보지 않는다. 사용자가 직접 준 물이든 자동 급수든 트레이에는 똑같이
 * 고인다 — 자동 급수 체인이 {@code AUTO} 만 이어받는 것과 판단 기준이 다르다.
 *
 * <p>실패를 밖으로 내지 않는다. 급수는 이미 끝났고 그 기록은 남았다. 트레이 판정이 실패해도
 * 다음 급수 때 다시 판정되므로 누락이 영구적이지 않다.
 */
@Component
public class DrainageTrayEvaluationListener {

    private static final Logger log = LoggerFactory.getLogger(DrainageTrayEvaluationListener.class);

    private final DrainageTrayService drainageTrayService;

    public DrainageTrayEvaluationListener(DrainageTrayService drainageTrayService) {
        this.drainageTrayService = drainageTrayService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(DeviceCommandCompletedEvent event) {
        if (event.commandType() != DeviceCommandType.WATER
                || event.status() != DeviceCommandStatus.OK) {
            return;
        }
        try {
            if (drainageTrayService.evaluate(event.plantId())) {
                log.info("Drainage tray alert opened: plantId={}", event.plantId());
            }
        } catch (RuntimeException exception) {
            log.warn("Drainage tray evaluation failed: plantId={}", event.plantId(), exception);
        }
    }
}
