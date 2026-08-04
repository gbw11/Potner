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
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @PatchMapping("/{alertId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "알림 목록에서 치우기",
            description = """
                    목록 조회에서 빠진다. **행을 지우지 않는다.**

                    지우면 세 가지가 어긋난다. 행복도 점수는 저장되지 않고 조회할 때마다 그 날짜의
                    알림을 세어 100 점에서 깎으므로 지난 점수가 올라간다. 자동 급수·말리기는
                    해소되지 않은 알림을 보고 트리거되고 열린 알림으로 중복 실행을 막으므로, 지우면
                    같은 알림이 새로 생기며 체인이 다시 시작된다. 그날 일기의 근거도 달라진다.

                    읽음도 함께 남긴다. 목록에서 사라졌는데 안 읽음으로 남으면 홈 배지만 켜져 있고
                    사용자는 무엇이 남았는지 찾을 수 없다.

                    이미 치운 알림에 다시 호출해도 처음 치운 시각을 유지한다.
                    """
    )
    public void dismiss(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String alertId
    ) {
        alertQueryService.dismiss(principal.userId(), alertId);
    }

    @DeleteMapping("/{alertId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "치우기 되돌리기",
            description = """
                    앱의 스와이프 되돌리기가 부른다. 목록에 다시 나타난다.

                    **읽음은 되돌리지 않는다.** 사용자가 그 알림을 실제로 봤다는 사실은 되돌리기로
                    사라지지 않는다.

                    치우지 않은 알림에 호출해도 204 다.
                    """
    )
    public void restore(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String alertId
    ) {
        alertQueryService.restore(principal.userId(), alertId);
    }
}
