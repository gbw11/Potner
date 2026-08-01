package com.potner.arrival.presentation;

import com.potner.arrival.application.ArrivalService;
import com.potner.arrival.dto.ArrivalEventRequest;
import com.potner.arrival.dto.ArrivalEventResponse;
import com.potner.arrival.dto.ArrivalEventStatusResponse;
import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/arrival/events")
@Tag(name = "귀가 감지", description = "임시 버튼 및 Android Geofence 귀가 이벤트 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ArrivalController {

    private final ArrivalService arrivalService;

    public ArrivalController(ArrivalService arrivalService) {
        this.arrivalService = arrivalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "귀가 접근 또는 취소 이벤트 발행")
    public ArrivalEventResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ArrivalEventRequest request
    ) {
        return arrivalService.process(principal.userId(), request);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "귀가 이벤트의 Jetson 처리 상태 조회")
    public ArrivalEventStatusResponse getStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String eventId
    ) {
        return arrivalService.getStatus(principal.userId(), eventId);
    }
}
