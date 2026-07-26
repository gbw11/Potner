package com.potner.alert.presentation;

import com.potner.alert.application.AlertQueryService;
import com.potner.alert.dto.AlertListResponse;
import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "알림", description = "센서 이상 알림 조회 및 읽음 처리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AlertController {

    private final AlertQueryService alertQueryService;

    public AlertController(AlertQueryService alertQueryService) {
        this.alertQueryService = alertQueryService;
    }

    @GetMapping
    @Operation(
            summary = "내 알림 목록 조회",
            description = """
                    알림이 만들어진 시각 기준 최신순으로 반환한다. 다른 사용자의 알림은 조회되지 않는다.
                    unreadOnly 와 activeOnly 는 함께 쓸 수 있다. size 가 상한을 넘으면 상한으로 줄이며
                    응답의 size 에 실제 적용값이 담긴다. 사용자에게 보여줄 문구는 서버가 만들지 않으므로
                    plantName, metricType, deviation 으로 조립한다. 시각은 모두 UTC 다.
                    """
    )
    public AlertListResponse getMyAlerts(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return alertQueryService.getMyAlerts(
                principal.userId(),
                unreadOnly,
                activeOnly,
                page,
                size
        );
    }

    @PatchMapping("/{alertId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "알림 읽음 처리",
            description = "이미 읽은 알림에 다시 호출해도 처음 확인 시각을 유지한다."
    )
    public void markRead(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String alertId
    ) {
        alertQueryService.markRead(principal.userId(), alertId);
    }
}
