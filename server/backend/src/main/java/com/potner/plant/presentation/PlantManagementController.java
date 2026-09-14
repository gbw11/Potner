package com.potner.plant.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.plant.application.PlantManagementService;
import com.potner.plant.dto.ChangeLifeStageRequest;
import com.potner.plant.dto.CreatePlantRequest;
import com.potner.plant.dto.GrowthProfileResponse;
import com.potner.plant.dto.PlantDetailResponse;
import com.potner.plant.dto.PlantListResponse;
import com.potner.plant.dto.UpdateGrowthProfileRequest;
import com.potner.plant.dto.UpdatePlantRequest;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plants")
@Tag(name = "내 식물", description = "내 식물과 식물별 맞춤 생육 기준 관리 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PlantManagementController {

    private final PlantManagementService plantManagementService;

    public PlantManagementController(PlantManagementService plantManagementService) {
        this.plantManagementService = plantManagementService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "식물 등록 및 기본 생육 기준 스냅샷 생성")
    public PlantDetailResponse createPlant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreatePlantRequest request
    ) {
        return plantManagementService.createPlant(principal.userId(), request);
    }

    @GetMapping
    @Operation(summary = "내 식물 목록 조회")
    public PlantListResponse getMyPlants(@AuthenticationPrincipal AuthenticatedUser principal) {
        return plantManagementService.getMyPlants(principal.userId());
    }

    @GetMapping("/{plantId}")
    @Operation(summary = "내 식물 상세 조회")
    public PlantDetailResponse getMyPlant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        return plantManagementService.getMyPlant(principal.userId(), plantId);
    }

    @PatchMapping("/{plantId}")
    @Operation(summary = "내 식물 기본 정보 수정")
    public PlantDetailResponse updatePlant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody UpdatePlantRequest request
    ) {
        return plantManagementService.updatePlant(principal.userId(), plantId, request);
    }

    @DeleteMapping("/{plantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "내 식물 삭제")
    public void deletePlant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        plantManagementService.deletePlant(principal.userId(), plantId);
    }

    @GetMapping("/{plantId}/growth-profile")
    @Operation(summary = "식물별 적용 생육 기준 조회")
    public GrowthProfileResponse getGrowthProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        return plantManagementService.getGrowthProfile(principal.userId(), plantId);
    }

    @PatchMapping("/{plantId}/growth-profile")
    @Operation(summary = "식물별 맞춤 생육 기준 수정")
    public GrowthProfileResponse updateGrowthProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestBody UpdateGrowthProfileRequest request
    ) {
        return plantManagementService.updateGrowthProfile(principal.userId(), plantId, request);
    }

    @PostMapping("/{plantId}/growth-profile/reset")
    @Operation(summary = "현재 종과 생장 단계의 기본 생육 기준으로 초기화")
    public GrowthProfileResponse resetGrowthProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        return plantManagementService.resetGrowthProfile(principal.userId(), plantId);
    }

    @PatchMapping("/{plantId}/growth-stage")
    @Operation(summary = "생장 단계 변경 및 새 단계 기본 기준 재적용")
    public PlantDetailResponse changeLifeStage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody ChangeLifeStageRequest request
    ) {
        return plantManagementService.changeLifeStage(principal.userId(), plantId, request);
    }
}
