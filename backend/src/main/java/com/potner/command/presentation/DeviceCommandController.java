package com.potner.command.presentation;

import com.potner.command.application.DeviceCommandService;
import com.potner.command.dto.DeviceCommandListResponse;
import com.potner.command.dto.DeviceCommandResponse;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 장치 명령의 발행과 이력 조회다.
 *
 * <p>명령은 비동기다. 발행 응답은 {@code ISSUED} 까지만 말해 주고, 수행 결과는 장치의 MQTT
 * 회신이 반영된 뒤 목록 조회로 본다. 발행이 곧 성공이 아니므로 201 이 아니라 202 다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/device-commands")
@Tag(name = "장치 명령", description = "급수·촬영 명령 발행 및 이력 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DeviceCommandController {

    private final DeviceCommandService deviceCommandService;

    public DeviceCommandController(DeviceCommandService deviceCommandService) {
        this.deviceCommandService = deviceCommandService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "장치 명령 발행",
            description = """
                    type 은 WATER(급수) / CAPTURE(촬영) / FAN(송풍) / NAVIGATE(이동)다.
                    급수·촬영·송풍은 스테이션의 라즈베리파이가, 이동은 젯슨이 받는다.

                    급수량은 요청으로 받지 않고 식물의 적용 생육 기준(recommendedWateringMl)을
                    쓴다. 기준에 급수량이 비어 있으면 400 (WATERING_AMOUNT_NOT_CONFIGURED) 이다.

                    NAVIGATE 는 destination(WATER_STATION/HOME/SUNLIGHT/GREETING)이 필수이고,
                    그 위치의 지도 좌표가 입력되어 있어야 한다. 좌표가 없으면 400
                    (LOCATION_POSE_NOT_CONFIGURED) 이다. FAN 은 seconds(1~300)를 줄 수 있고
                    생략하면 서버 기본값을 쓴다.

                    같은 식물에 같은 종류의 명령이 회신 대기 중이면 409
                    (DEVICE_COMMAND_ALREADY_PENDING) 다. 장치가 죽어 회신이 없으면 서버가
                    제한 시간 뒤 TIMED_OUT 으로 끊으므로 영구히 막히지는 않는다.

                    응답의 status 는 항상 ISSUED 다. 수행 결과는 목록 조회에서 확인한다.
                    """
    )
    public DeviceCommandResponse issue(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody IssueDeviceCommandRequest request
    ) {
        return deviceCommandService.issue(principal.userId(), plantId, request);
    }

    @GetMapping
    @Operation(
            summary = "장치 명령 이력 조회",
            description = """
                    최신순이다. from 과 to 는 서비스 타임존 기준 날짜(yyyy-MM-dd)이며 양쪽 모두
                    포함이다. dispensedMl 은 실제 급수량으로, 펌프 상한에 걸리면 요청량보다 작을
                    수 있다.
                    """
    )
    public DeviceCommandListResponse getCommands(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return deviceCommandService.getCommands(principal.userId(), plantId, from, to);
    }
}
