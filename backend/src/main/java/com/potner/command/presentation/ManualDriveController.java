package com.potner.command.presentation;

import com.potner.command.application.ManualDriveService;
import com.potner.command.dto.DriveRequest;
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
 * 방향 버튼으로 로봇을 직접 미는 조작이다. 지도 좌표 없이 바퀴를 움직여 볼 수 있는 통로다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/drive")
@Tag(name = "장치 명령", description = "급수·촬영 명령 발행 및 이력 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ManualDriveController {

    private final ManualDriveService manualDriveService;

    public ManualDriveController(ManualDriveService manualDriveService) {
        this.manualDriveService = manualDriveService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "수동 주행",
            description = """
                    direction 은 FORWARD / BACKWARD / LEFT / RIGHT / STOP 다. LEFT·RIGHT 는
                    제자리 회전이다. 젯슨이 받는다.

                    **목적지 이동(device-commands 의 NAVIGATE)과 다른 통로다.** 지도 좌표를 보지
                    않으므로 SLAM 지도가 없는 자리에서도 바퀴가 도는지 확인할 수 있다.

                    속도는 요청으로 받지 않고 서버 설정(potner.drive.*)을 쓴다. 사람 옆에서 움직
                    이는 장치의 속도를 조작하는 쪽이 정할 수는 없다.

                    durationMs(100~3000)는 이 시간만 움직이고 멈추라는 뜻이며 생략하면 서버
                    기본값이다. **로봇은 이 시간이 지나면 스스로 멈춰야 한다** — 앱이 죽거나
                    와이파이가 끊겨 STOP 이 못 나가는 경우를 서버가 막을 방법이 없으므로 이것이
                    유일한 안전장치다. STOP 에는 쓰이지 않는다.

                    **이력을 남기지 않고 회신도 기다리지 않는다.** 방향 버튼은 연달아 눌리는 것이
                    정상이라 device_command 의 409(DEVICE_COMMAND_ALREADY_PENDING) 로 막으면 두
                    번째 누름부터 전부 막힌다. 그래서 발행만 하고 202 다. 수행 여부는 로봇을 눈으로
                    봐야 한다.

                    식물에 배정된 로봇이 없으면 404 (PLANT_ASSIGNMENT_NOT_FOUND), 그 로봇에 바퀴가
                    달린 장치가 등록되어 있지 않으면 404 (COMMAND_DEVICE_NOT_FOUND) 다.

                    브로커가 꺼진 환경에서는 발행기가 no-op 이라 202 를 받고도 아무것도 나가지
                    않는다. 서버 로그의 "Manual drive published" 로 확인한다.
                    """
    )
    public void drive(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody DriveRequest request
    ) {
        manualDriveService.drive(principal.userId(), plantId, request);
    }
}
