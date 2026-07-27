package com.potner.device.dto;

import com.potner.device.domain.IotDeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 로봇 하위 IoT 장치 등록 요청이다.
 *
 * <p>측정값이 저장되려면 이 행이 있어야 한다. 유입 경로가
 * {@code device_uid → iot_device → robot → plant_device_assignment → plant} 이기 때문이다.
 */
public record RegisterIotDeviceRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9_-]*",
                message = "장치 식별자는 영문, 숫자, 하이픈, 밑줄만 사용할 수 있습니다."
        )
        String deviceUid,

        @NotNull
        IotDeviceType deviceType
) {
}
