package com.potner.plant.dto;

import java.util.List;

public record GrowthStageListResponse(
        String speciesId,
        String speciesName,
        List<GrowthStageResponse> growthStages
) {
    public GrowthStageListResponse {
        growthStages = List.copyOf(growthStages);
    }
}
