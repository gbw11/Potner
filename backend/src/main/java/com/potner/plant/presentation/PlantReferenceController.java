package com.potner.plant.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.plant.application.PlantReferenceService;
import com.potner.plant.dto.GrowthRequirementResponse;
import com.potner.plant.dto.GrowthStageListResponse;
import com.potner.plant.dto.PlantSpeciesListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plant-species")
@Tag(name = "식물 기준정보", description = "식물 종, 지원 생장 단계 및 기본 생육 기준 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PlantReferenceController {

    private final PlantReferenceService plantReferenceService;

    public PlantReferenceController(PlantReferenceService plantReferenceService) {
        this.plantReferenceService = plantReferenceService;
    }

    @GetMapping
    @Operation(summary = "활성 식물 종 목록 조회")
    public PlantSpeciesListResponse getActiveSpecies() {
        return plantReferenceService.getActiveSpecies();
    }

    @GetMapping("/{speciesId}/growth-stages")
    @Operation(summary = "식물 종에서 지원하는 생장 단계 조회")
    public GrowthStageListResponse getAvailableGrowthStages(@PathVariable String speciesId) {
        return plantReferenceService.getAvailableGrowthStages(speciesId);
    }

    @GetMapping("/{speciesId}/growth-stages/{lifeStageId}/requirement")
    @Operation(summary = "식물 종과 생장 단계의 기본 생육 기준 조회")
    public GrowthRequirementResponse getGrowthRequirement(
            @PathVariable String speciesId,
            @PathVariable String lifeStageId
    ) {
        return plantReferenceService.getGrowthRequirement(speciesId, lifeStageId);
    }
}
