package com.potner.plant.dto;

import java.util.List;

/**
 * 식물 등록 화면의 대분류·소분류 선택에 쓰는 계층이다.
 * 루트 분류는 노출하지 않으며, 선택 가능한 종이 없는 분류도 제외한다.
 */
public record PlantCategoryTreeResponse(
        List<PlantCategoryWithSpeciesResponse> categories
) {
    public PlantCategoryTreeResponse {
        categories = List.copyOf(categories);
    }
}
