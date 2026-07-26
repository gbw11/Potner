package com.potner.plant.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 부분 수정 요청이다. 보내지 않은 항목은 바뀌지 않으며 값을 비우는 것은 지원하지 않는다.
 */
public record UpdatePlantRequest(
        @Size(max = 50, message = "식물 이름은 50자 이하여야 합니다.")
        String name,

        LocalDate adoptedDate
) {
}
