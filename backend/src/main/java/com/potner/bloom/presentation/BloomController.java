package com.potner.bloom.presentation;

import com.potner.bloom.application.BloomService;
import com.potner.bloom.dto.BloomListResponse;
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

/**
 * 사용자 단위 개화 기록 조회다.
 *
 * <p>이상 알림과 경로를 나눈 이유는 화면이 이미 나뉘어 있기 때문이다. 알림 화면이 '이상 알림 /
 * 개화 알림 / 알림 이력' 탭을 따로 두므로 한 목록으로 합쳐 보내면 앱이 다시 갈라야 한다.
 */
@RestController
@RequestMapping("/api/v1/blooms")
@Tag(name = "개화 기록", description = "개화 기록 조회 및 읽음 처리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class BloomController {

    private final BloomService bloomService;

    public BloomController(BloomService bloomService) {
        this.bloomService = bloomService;
    }

    @GetMapping
    @Operation(
            summary = "내 개화 기록 목록 조회",
            description = """
                    개화한 날 기준 최신순으로 반환한다. 다른 사용자의 기록은 조회되지 않는다.
                    size 가 상한(100)을 넘으면 상한으로 줄이며 응답의 size 에 실제 적용값이 담긴다.

                    bloomDate 는 서비스 타임존 기준 날짜이고 createdAt 은 UTC 다.
                    """
    )
    public BloomListResponse getMyBlooms(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return bloomService.getMyBlooms(principal.userId(), unreadOnly, page, size);
    }

    @PatchMapping("/{bloomId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "개화 기록 읽음 처리",
            description = "이미 읽은 기록에 다시 호출해도 처음 확인 시각을 유지한다."
    )
    public void markRead(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String bloomId
    ) {
        bloomService.markRead(principal.userId(), bloomId);
    }
}
