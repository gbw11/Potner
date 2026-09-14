package com.potner.device.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.device.application.DeviceQueryService;
import com.potner.device.dto.PlantDeviceListResponse;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plants/{plantId}/devices")
@Tag(name = "장치", description = "식물에 연결된 로봇 및 IoT 장치 상태 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DeviceQueryController {

    private final DeviceQueryService deviceQueryService;

    public DeviceQueryController(DeviceQueryService deviceQueryService) {
        this.deviceQueryService = deviceQueryService;
    }

    @GetMapping
    @Operation(
            summary = "식물 담당 로봇 및 장치 연결 상태 조회",
            description = """
                    로봇이 아직 배정되지 않았으면 robot 은 null 이고 devices 는 빈 목록이다.
                    404 가 아니므로 앱은 "장치 미연결" 상태를 그대로 표시하면 된다.

                    robot 의 connectionStatus 와 lastSeenAt 은 저장된 값이 아니라 하위 장치 상태에서
                    계산한 값이다. 장치 하나라도 ONLINE 이면 로봇도 ONLINE 이다.
                    batteryPercent 는 젯슨의 status/battery 로 수집한다. 한 번도 받지 못했으면 null 이다.
                    firmwareVersion 은 장치가 보내는 토픽이 없어 항상 null 이다.

                    장치는 종류 순으로 정렬한다. 시각은 모두 UTC 다.
                    """
    )
    public PlantDeviceListResponse getPlantDevices(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        return deviceQueryService.getPlantDevices(principal.userId(), plantId);
    }
}
