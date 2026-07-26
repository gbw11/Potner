package com.potner.plant.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.plant.application.PlantReferenceService;
import com.potner.plant.dto.PlantCategoryTreeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plant-categories")
@Tag(name = "식물 분류", description = "식물 등록 화면의 대분류 및 소분류 선택 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PlantCategoryController {

    private final PlantReferenceService plantReferenceService;

    public PlantCategoryController(PlantReferenceService plantReferenceService) {
        this.plantReferenceService = plantReferenceService;
    }

    @GetMapping
    @Operation(
            summary = "대분류와 소분류(종) 계층 조회",
            description = """
                    대분류는 categories, 소분류는 각 대분류의 species 다. 루트 분류는 노출하지 않으며
                    선택 가능한 종이 없는 분류도 빠진다. sortOrder 순으로 정렬되어 있다.

                    자람 수준은 종에 따라 다르므로 여기서 함께 주지 않는다.
                    종을 고른 뒤 GET /api/v1/plant-species/{speciesId}/growth-stages 로 조회한다.
                    예를 들어 바질은 개화기가 없고 재생장기가 있으며, 칼란디바는 발아기가 없고
                    꽃눈형성기가 있다.
                    """
    )
    public PlantCategoryTreeResponse getCategoryTree() {
        return plantReferenceService.getCategoryTree();
    }
}
