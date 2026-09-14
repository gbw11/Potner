package com.potner.plant.dto;

import com.potner.plant.domain.PlantSpecies;

import java.util.List;

/**
 * 등록 화면의 소분류 항목이다. 종과 그 종이 지원하는 자람 수준을 함께 담는다.
 *
 * <p>{@link PlantSpeciesResponse}와 따로 두는 이유는 그 record가 내 식물 상세에도 임베드되기
 * 때문이다. 거기에는 종의 전체 자람 수준 목록이 필요하지 않다.
 */
public record PlantSpeciesWithStagesResponse(
        String speciesId,
        String name,
        String scientificName,
        String description,
        List<GrowthStageResponse> growthStages
) {
    public PlantSpeciesWithStagesResponse {
        growthStages = List.copyOf(growthStages);
    }

    public static PlantSpeciesWithStagesResponse of(
            PlantSpecies species,
            List<GrowthStageResponse> growthStages
    ) {
        return new PlantSpeciesWithStagesResponse(
                species.getId(),
                species.getName(),
                species.getScientificName(),
                species.getDescription(),
                growthStages
        );
    }
}
