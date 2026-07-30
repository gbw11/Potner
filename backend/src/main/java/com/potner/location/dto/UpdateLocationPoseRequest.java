package com.potner.location.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 좌표 입력 요청이다. RViz 에서 읽은 map 프레임 좌표(m, rad)를 설치자가 넣는다.
 *
 * <p>셋 다 필수다. 지도를 다시 그리면 세 값이 모두 무효가 되므로 부분 수정을 두지 않는다.
 * yaw 는 -pi ~ pi 로 정규화된 값이어야 한다 — 로봇 쪽 좌표 규약과 같다.
 */
public record UpdateLocationPoseRequest(
        @NotNull(message = "x 좌표는 필수입니다.")
        @DecimalMin(value = "-99999.999") @DecimalMax(value = "99999.999")
        BigDecimal x,
        @NotNull(message = "y 좌표는 필수입니다.")
        @DecimalMin(value = "-99999.999") @DecimalMax(value = "99999.999")
        BigDecimal y,
        @NotNull(message = "yaw 는 필수입니다.")
        @DecimalMin(value = "-3.1416") @DecimalMax(value = "3.1416")
        BigDecimal yaw
) {
}
