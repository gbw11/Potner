package com.potner.device.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.device.application.DeviceRegistrationService;
import com.potner.device.dto.DeviceUploadTokenResponse;
import com.potner.device.dto.IotDeviceResponse;
import com.potner.device.dto.RegisterIotDeviceRequest;
import com.potner.device.dto.RegisterRobotRequest;
import com.potner.device.dto.RobotListResponse;
import com.potner.device.dto.RobotRegistrationResponse;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/robots")
@Tag(name = "로봇 등록", description = "로봇 및 하위 IoT 장치 등록 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class RobotController {

    private final DeviceRegistrationService deviceRegistrationService;

    public RobotController(DeviceRegistrationService deviceRegistrationService) {
        this.deviceRegistrationService = deviceRegistrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "로봇 등록",
            description = """
                    deviceUid 는 로봇 스티커의 식별자다. MQTT 토픽 세그먼트, 페이로드 deviceId,
                    Mosquitto 계정명과 같은 값이어야 하며 등록 후에는 바꿀 수 없다. 네 곳 중 하나라도
                    다르면 측정값이 조용히 버려진다.

                    robot 과 iot_device 를 통틀어 이미 쓰인 deviceUid 면 409 다.

                    등록만으로는 측정값이 저장되지 않는다. 하위 IoT 장치를 등록하고
                    식물에 배정까지 해야 유입 경로가 완성된다.

                    uploadToken 원문은 이 응답에서만 볼 수 있다. 서버는 해시만 저장하므로 다시
                    조회할 수 없다. 사용자가 라즈베리 설정에 넣도록 안내해야 하며 잃어버리면
                    재발급해야 한다.
                    """
    )
    public RobotRegistrationResponse registerRobot(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody RegisterRobotRequest request
    ) {
        return deviceRegistrationService.registerRobot(principal.userId(), request);
    }

    @DeleteMapping("/{robotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "로봇 해제",
            description = """
                    로봇과 하위 IoT 장치를 함께 해제한다. 기기를 교체하거나 다른 사람에게 넘길 때
                    쓴다. 활성 식물 배정도 함께 풀린다.

                    물리 삭제가 아니다. 측정 이력·명령 이력은 원래 식물에 그대로 남고, 해제된
                    deviceUid 는 다른 계정이 새로 등록할 수 있다.

                    되돌릴 수 없다. 같은 기기를 다시 쓰려면 새로 등록해야 하고, 그때 uploadToken 도
                    새로 발급된다.
                    """
    )
    public void releaseRobot(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String robotId
    ) {
        deviceRegistrationService.releaseRobot(principal.userId(), robotId);
    }

    @PostMapping("/{robotId}/upload-token")
    @Operation(
            summary = "업로드 토큰 재발급",
            description = """
                    토큰을 잃어버렸거나 유출됐을 때 쓴다. 재발급 즉시 이전 토큰이 무효가 되므로
                    라즈베리 설정도 함께 바꿔야 한다. 원문은 이 응답에서만 볼 수 있다.
                    """
    )
    public DeviceUploadTokenResponse reissueUploadToken(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String robotId
    ) {
        return deviceRegistrationService.reissueUploadToken(principal.userId(), robotId);
    }

    @GetMapping
    @Operation(
            summary = "내 로봇 목록 조회",
            description = """
                    이름순으로 정렬한다. 한 대도 없으면 robots 는 빈 배열이다.

                    connectionStatus 와 lastSeenAt 은 저장된 값이 아니라 하위 장치 상태에서 파생한다.
                    장치 하나라도 ONLINE 이면 로봇도 ONLINE 이다.

                    assignedPlantId 가 null 이면 아직 어느 식물도 담당하지 않으며 측정값이 저장되지 않는다.
                    시각은 모두 UTC 다.
                    """
    )
    public RobotListResponse getRobots(@AuthenticationPrincipal AuthenticatedUser principal) {
        return deviceRegistrationService.getRobots(principal.userId());
    }

    @PostMapping("/{robotId}/devices")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "로봇 하위 IoT 장치 등록",
            description = """
                    센서를 읽어 MQTT 로 발행하는 장치다. 이 행이 없으면 측정값이 저장되지 않는다.

                    deviceUid 는 MQTT 토픽 세그먼트와 페이로드 deviceId 와 같아야 한다.
                    이미 쓰인 값이면 409, 타인 로봇이면 404 다.
                    """
    )
    public IotDeviceResponse registerIotDevice(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String robotId,
            @Valid @RequestBody RegisterIotDeviceRequest request
    ) {
        return deviceRegistrationService.registerIotDevice(principal.userId(), robotId, request);
    }
}
