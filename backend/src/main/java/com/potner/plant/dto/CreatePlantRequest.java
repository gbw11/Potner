package com.potner.plant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePlantRequest(
        @NotBlank(message = "식물 종 ID는 필수입니다.")
        String speciesId,

        @NotBlank(message = "생장 단계 ID는 필수입니다.")
        String lifeStageId,

        @NotBlank(message = "식물 이름은 필수입니다.")
        @Size(max = 50, message = "식물 이름은 50자 이하여야 합니다.")
        String name
) {
}
