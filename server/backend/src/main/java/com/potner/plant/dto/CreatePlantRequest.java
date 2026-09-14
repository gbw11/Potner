package com.potner.plant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreatePlantRequest(
        @NotBlank(message = "식물 종 ID는 필수입니다.")
        String speciesId,

        @NotBlank(message = "생장 단계 ID는 필수입니다.")
        String lifeStageId,

        @NotBlank(message = "식물 이름은 필수입니다.")
        @Size(max = 50, message = "식물 이름은 50자 이하여야 합니다.")
        String name,

        /**
         * 데려온 날짜다. 모를 수 있으므로 필수가 아니다.
         *
         * <p>미래 날짜를 서버에서 거부하지 않는다. 타임존이 없는 순수 날짜라 "오늘"을 판단하려면
         * 사용자의 타임존을 알아야 하는데 서버는 그것을 모른다. 한국 시간 자정 직후에 사용자가
         * 자기 기준 오늘을 보내면 UTC 기준으로는 미래가 되어 잘못 거부된다.
         * 미래 날짜 제한은 앱의 날짜 선택 위젯에서 처리한다.
         */
        LocalDate adoptedDate
) {
}
