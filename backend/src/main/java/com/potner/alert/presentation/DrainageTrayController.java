package com.potner.alert.presentation;

import com.potner.alert.application.DrainageTrayService;
import com.potner.config.OpenApiConfig;
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

@RestController
@RequestMapping("/api/v1/plants/{plantId}/drainage-tray")
@Tag(name = "배수트레이", description = "배수트레이 비움 처리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DrainageTrayController {

    private final DrainageTrayService drainageTrayService;

    public DrainageTrayController(DrainageTrayService drainageTrayService) {
        this.drainageTrayService = drainageTrayService;
    }

    @PostMapping("/emptied")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "배수트레이를 비웠음을 기록",
            description = """
                    활성 배수트레이 알림을 해제하고 읽음으로 표시한다. 해제 시각이 다음 누적 급수량의
                    기준점이 되므로, 이 호출 이후의 급수만 다시 센다.

                    알림 읽음(PATCH /alerts/{alertId}/read)과 다르다. 읽음은 "확인했다" 이고 이것은
                    "비웠다" 다. 읽음만 처리하면 누적이 리셋되지 않아 다음 급수에서 알림이 다시 뜨지
                    않는다.

                    활성 알림이 없으면 404 다. 비울 필요가 없는 상태에서 호출하면 기준점이 앞당겨져
                    다음 알림이 늦어지므로 조용히 넘기지 않는다.

                    비웠는지 서버가 검증하지 않는다. 사용자를 신뢰하는 설계다.
                    """
    )
    public void markEmptied(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        drainageTrayService.markEmptied(principal.userId(), plantId);
    }
}
