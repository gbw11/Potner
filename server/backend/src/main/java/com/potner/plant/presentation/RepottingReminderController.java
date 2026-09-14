package com.potner.plant.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.plant.application.RepottingReminderService;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분갈이 시기 알림 발송이다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/repot-reminder")
@Tag(name = "식물", description = "식물 관리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class RepottingReminderController {

    private final RepottingReminderService repottingReminderService;

    public RepottingReminderController(RepottingReminderService repottingReminderService) {
        this.repottingReminderService = repottingReminderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "분갈이 시기 알림 발송",
            description = """
                    그 식물의 소유자 기기로 분갈이 안내 푸시를 보낸다. 알림을 누르면 앱의
                    분갈이 방법 화면(`/repotting`)이 열린다.

                    **시기를 서버가 판정하지 않는다.** 판정하려면 "마지막으로 분갈이한 날" 이
                    있어야 하는데 그건 사용자가 기록해야 하는 값이고 담을 테이블이 아직 없다.
                    지금은 앱이 식물 등록일로 D-day 를 계산해 화면에 안내하고, 알림은 이
                    통로로 보낸다.

                    **이력을 남기지 않는다.** 같은 식물에 두 번 부르면 두 번 나간다. 중복을
                    막을 기준("이미 이번 주기에 알렸다")이 마지막 분갈이 날짜 없이는 정의되지
                    않는다.

                    `alert` 테이블에도 기록하지 않으므로 앱 알림 목록에는 남지 않는다. 분갈이는
                    해소되는 상태가 아니라 한 번 알리면 끝나는 안내다.

                    발송만 하고 기기 수신을 확인하지 않으므로 202 다. 서버 로그의
                    "Repotting reminder push handled" 로 발송 결과를 확인한다.

                    그 사용자의 식물이 아니거나 삭제됐으면 404 (PLANT_NOT_FOUND) 다. 알림 수신
                    설정이 꺼져 있거나 등록된 기기가 없으면 404 (PUSH_TARGET_NOT_FOUND) 다 -
                    버튼을 눌렀는데 아무 일도 일어나지 않는 이유를 알려 주려는 것이다.
                    """
    )
    public void send(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        repottingReminderService.send(principal.userId(), plantId);
    }
}
