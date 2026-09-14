package com.potner.command.presentation;

import com.potner.command.application.CareRunService;
import com.potner.command.dto.DeviceCommandResponse;
import com.potner.command.dto.StartCareRunRequest;
import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자동 케어 한 회차를 지금 시작한다.
 *
 * <p>{@code /device-commands} 와 목적이 다르다. 그쪽은 명령 하나를 그대로 장치에 보내는 수동
 * 조작이라 체인이 이어지지 않고, 이동은 사용자 배치로 기록되어 자동 재배치가 한동안 비켜선다.
 * 이쪽은 자동 케어의 시작 조건만 대신 만들어 주고 나머지는 서버가 지휘한다.
 *
 * <p>명령과 마찬가지로 비동기다. 응답은 첫 명령이 {@code ISSUED} 되었다는 것까지이고, 이후
 * 진행은 {@code GET /device-commands} 이력에 단계별로 나타난다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/care-runs")
@Tag(name = "자동 케어 실행", description = "자동 케어 한 회차를 즉시 시작하는 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CareRunController {

    private final CareRunService careRunService;

    public CareRunController(CareRunService careRunService) {
        this.careRunService = careRunService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "자동 케어 한 회차 시작",
            description = """
                    평소에는 조건이 갖춰져야 도는 자동 케어를 지금 한 회차 돌린다. 건너뛰는 것은
                    시작 조건뿐이고, 이동 → 작업 → 복귀와 단계 연결은 평소 경로 그대로다.

                    purpose 별로 하는 일이 다르다.
                      WATERING   이동(스테이션) → 급수 → 송풍 → 복귀
                      DRYING     이동(스테이션) → 송풍 → 복귀
                      CAPTURE    이동(스테이션) → 촬영 → 복귀
                      RELOCATION 이동(햇빛 자리)

                    넷 다 이동으로 시작하므로 목적지 좌표가 등록돼 있어야 한다. 없으면 404
                    (ROBOT_LOCATION_NOT_FOUND) 또는 400 (LOCATION_POSE_NOT_CONFIGURED) 이다.

                    해당 케어가 꺼져 있으면 409 (CARE_RUN_DISABLED) 다. 첫 이동만 나가고
                    체인을 이을 리스너가 회신을 무시해 로봇이 스테이션에 남기 때문이다.

                    로봇이 다른 작업 중이면 409 (CARE_RUN_ROBOT_BUSY) 다. 급수는 급수량이
                    설정돼 있어야 하며(400), 로봇이 스테이션에 도착한 뒤에 실패하지 않도록
                    발행 전에 확인한다.

                    응답은 첫 명령이다. 진행은 GET /device-commands 로 확인한다.
                    """
    )
    public DeviceCommandResponse start(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody StartCareRunRequest request
    ) {
        return careRunService.start(principal.userId(), plantId, request.purpose());
    }
}
