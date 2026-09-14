package com.potner.sensor.presentation;

import com.potner.sensor.application.DeviceSensorQueryService;
import com.potner.sensor.dto.CurrentSensorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장치가 담당 식물의 센서 최신값을 조회하는 경로다.
 *
 * <p>사용자 JWT 대신 업로드 토큰으로 인증하므로 Security 설정에서 JWT 인증을 면제하고
 * 서비스가 토큰을 직접 검증한다. <strong>plantId 를 받지 않는다</strong> — 어느 식물인지는
 * 로봇의 활성 배정에서 서버가 정한다. 장치가 지정할 수 있으면 토큰 하나로 남의 식물
 * 데이터를 읽을 수 있다.
 */
@RestController
@RequestMapping("/api/v1/device/sensors")
@Tag(name = "장치 센서 조회", description = "젯슨 로봇이 담당 식물의 센서 최신값을 조회하는 API")
public class DeviceSensorController {

    private final DeviceSensorQueryService deviceSensorQueryService;

    public DeviceSensorController(DeviceSensorQueryService deviceSensorQueryService) {
        this.deviceSensorQueryService = deviceSensorQueryService;
    }

    @GetMapping("/current")
    @Operation(
            summary = "장치 센서 최신값 조회",
            description = """
                    X-Device-Token 헤더에 로봇 등록 시 발급된 업로드 토큰을 넣는다.
                    사진 업로드와 같은 토큰이다.

                    plantId 는 받지 않는다. 로봇의 활성 배정에서 서버가 정한다. 배정이 없으면
                    404 (PLANT_ASSIGNMENT_NOT_FOUND), 토큰이 틀리거나 없으면 401 이다.

                    응답 형식은 GET /api/v1/plants/{plantId}/sensors/current 와 같다.
                    시각은 모두 UTC 다.
                    """
    )
    public CurrentSensorResponse getCurrentSensors(
            // required=false 인 이유: 전역 예외 처리기가 MissingRequestHeaderException 을
            // 다루지 않아 required=true 로 두면 헤더 누락이 401 이 아니라 500 으로 나간다.
            // 누락은 서비스의 blank 검사가 401 로 떨어뜨린다.
            @RequestHeader(value = "X-Device-Token", required = false) String uploadToken
    ) {
        return deviceSensorQueryService.getCurrentSensors(uploadToken);
    }
}
