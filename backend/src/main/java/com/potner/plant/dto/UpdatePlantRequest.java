package com.potner.plant.dto;

import jakarta.validation.constraints.Size;

public record UpdatePlantRequest(
        @Size(max = 50, message = "식물 이름은 50자 이하여야 합니다.")
        String name
) {
}
