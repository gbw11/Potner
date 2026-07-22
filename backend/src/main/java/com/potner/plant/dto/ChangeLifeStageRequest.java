package com.potner.plant.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeLifeStageRequest(
        @NotBlank(message = "생장 단계 ID는 필수입니다.")
        String lifeStageId
) {
}
