package com.potner.user.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import com.potner.user.application.NotificationSettingService;
import com.potner.user.dto.NotificationSettingResponse;
import com.potner.user.dto.UpdateNotificationSettingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/notification-settings")
@Tag(name = "알림 설정", description = "사용자 알림 수신 설정 조회 및 변경 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    public NotificationSettingController(NotificationSettingService notificationSettingService) {
        this.notificationSettingService = notificationSettingService;
    }

    @GetMapping
    @Operation(
            summary = "내 알림 설정 조회",
            description = """
                    설정을 한 번도 바꾸지 않은 사용자는 기본값을 돌려준다.
                    allEnabled, pushEnabled, plantCareEnabled 는 켜진 상태이고
                    marketingEnabled 는 회원가입의 선택 동의 항목이라 꺼진 상태다.
                    """
    )
    public NotificationSettingResponse getMySettings(
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return notificationSettingService.getMySettings(principal.userId());
    }

    @PatchMapping
    @Operation(
            summary = "내 알림 설정 변경",
            description = """
                    보낸 항목만 바뀌고 나머지는 유지된다. allEnabled 는 마스터 스위치이며
                    끄면 세부 설정과 무관하게 발송하지 않는다. 변경 후 전체 설정을 돌려준다.
                    """
    )
    public NotificationSettingResponse updateMySettings(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody UpdateNotificationSettingRequest request
    ) {
        return notificationSettingService.updateMySettings(principal.userId(), request);
    }
}
