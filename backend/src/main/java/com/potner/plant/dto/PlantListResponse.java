package com.potner.plant.dto;

import java.util.List;

public record PlantListResponse(List<PlantSummaryResponse> plants) {
    public PlantListResponse {
        plants = List.copyOf(plants);
    }
}
