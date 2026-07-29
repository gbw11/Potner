package com.potner.bloom.presentation;

import com.potner.bloom.application.BloomService;
import com.potner.bloom.dto.BloomResponse;
import com.potner.bloom.dto.RecordBloomRequest;
import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 식물의 개화를 기록하고 지운다.
 *
 * <p>조회가 여기 없는 이유는 사용자가 개화를 식물별이 아니라 알림 화면에서 모아 보기 때문이다.
 * 목록은 {@code GET /api/v1/blooms} 다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/blooms")
@Tag(name = "개화 기록", description = "개화 기록 조회 및 읽음 처리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PlantBloomController {

    private final BloomService bloomService;

    public PlantBloomController(BloomService bloomService) {
        this.bloomService = bloomService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "개화 기록",
            description = """
                    꽃이 핀 것을 남긴다. 기록되면 사용자 기기로 푸시가 나가며, 그 식물의 첫 꽃일
                    때와 아닐 때의 문구가 다르다.

                    bloomDate 를 비우면 서버가 서비스 타임존 기준 오늘로 채운다. 보통은 비운 채로
                    보내면 된다. 서버가 아는 오늘보다 미래 날짜는 400 이다.

                    같은 날짜로 여러 번 기록할 수 있다. 하루에 두 송이가 피는 경우가 있어 날짜로
                    막지 않는다. 잘못 누른 것은 삭제로 정리한다.
                    """
    )
    public BloomResponse record(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody(required = false) RecordBloomRequest request
    ) {
        // 메모도 날짜도 없이 누르는 것이 기본 사용이라 본문 자체가 비어 올 수 있다.
        RecordBloomRequest resolved = request == null
                ? new RecordBloomRequest(null, null)
                : request;
        return bloomService.record(principal.userId(), plantId, resolved);
    }

    @DeleteMapping("/{bloomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "개화 기록 삭제",
            description = "잘못 남긴 기록을 지운다. 물리 삭제라 목록에서 완전히 사라진다."
    )
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @PathVariable String bloomId
    ) {
        bloomService.delete(principal.userId(), plantId, bloomId);
    }
}
