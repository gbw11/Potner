package com.potner.push.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.push.application.FcmTokenService;
import com.potner.push.dto.RegisterFcmTokenRequest;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/fcm-tokens")
@Tag(name = "FCM 토큰", description = "기기별 FCM 등록 토큰 관리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class FcmTokenController {

    private final FcmTokenService fcmTokenService;

    public FcmTokenController(FcmTokenService fcmTokenService) {
        this.fcmTokenService = fcmTokenService;
    }

    @PutMapping("/{installationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "기기 등록 및 갱신",
            description = """
                    Firebase 설치 ID 를 키로 기기를 등록한다. 앱이 로그인과 세션 복원마다,
                    그리고 FCM 토큰이 회전할 때마다 부르므로 같은 설치 ID 로 반복 호출해도 안전하다.

                    같은 기기를 다른 사용자가 등록하면 이전 사용자의 등록을 대체한다.
                    세션 만료로 강제 로그아웃된 사용자의 등록이 남아 있어도 그 사용자에게
                    알림이 가지 않도록 하기 위함이다.
                    """
    )
    public void register(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String installationId,
            @Valid @RequestBody RegisterFcmTokenRequest request
    ) {
        fcmTokenService.register(principal.userId(), installationId, request);
    }

    @DeleteMapping("/{installationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "기기 등록 해제",
            description = """
                    로그아웃 시 호출한다. 이미 해제된 설치 ID 로 다시 불러도 204 를 돌려주므로
                    앱이 실패 후 재시도할 수 있다.

                    본인이 등록한 기기만 해제된다. 남의 설치 ID 를 넣어도 204 이지만 그 등록은
                    지워지지 않는다.
                    """
    )
    public void unregister(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String installationId
    ) {
        fcmTokenService.unregister(principal.userId(), installationId);
    }
}
