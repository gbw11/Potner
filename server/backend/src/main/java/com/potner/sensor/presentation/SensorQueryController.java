package com.potner.sensor.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.security.AuthenticatedUser;
import com.potner.sensor.application.SensorQueryService;
import com.potner.sensor.domain.SensorHistoryInterval;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.dto.CurrentSensorResponse;
import com.potner.sensor.dto.SensorHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/plants/{plantId}/sensors")
@Tag(name = "센서", description = "식물 센서 최신값 및 이력 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SensorQueryController {

    private final SensorQueryService sensorQueryService;

    public SensorQueryController(SensorQueryService sensorQueryService) {
        this.sensorQueryService = sensorQueryService;
    }

    @GetMapping("/current")
    @Operation(
            summary = "센서 최신값 및 상태 판정 조회",
            description = """
                    센서 4종의 최신 측정값을 항상 함께 반환한다. 측정값이 없으면 status 는 NO_DATA 이고,
                    최신값이 허용 신선도를 넘겼으면 STALE 이다. 조도는 순간값으로 판정하지 않으므로
                    NOT_APPLICABLE 을 반환한다. 시각은 모두 UTC 다.
                    """
    )
    public CurrentSensorResponse getCurrentSensors(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        return sensorQueryService.getCurrentSensors(principal.userId(), plantId);
    }

    @GetMapping("/history")
    @Operation(
            summary = "센서 이력 구간 집계 조회",
            description = """
                    지정 기간의 측정값을 시간 또는 일 단위로 집계해 반환한다. 구간 경계는 서비스 타임존
                    기준으로 계산하므로 일 단위 집계는 한국 시간 자정에 끊긴다. 응답 시각은 모두 UTC 다.

                    from 과 to 는 오프셋을 포함한 ISO-8601 형식이어야 한다. 오프셋이 없으면 UTC 로
                    단정하지 않고 400 을 돌려준다. 쿼리 스트링에서 '+' 는 공백으로 해석되므로
                    'Z' 형식을 권장한다. 예: 2026-07-26T00:00:00Z
                    (+09:00 을 쓰려면 %2B09:00 으로 인코딩해야 한다.)

                    조회 기간 상한은 interval 에 따라 다르다. HOUR 는 14일, DAY 는 365일이다.
                    """
    )
    public SensorHistoryResponse getHistory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestParam SensorType sensorType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "HOUR") SensorHistoryInterval interval
    ) {
        return sensorQueryService.getHistory(
                principal.userId(),
                plantId,
                sensorType,
                from,
                to,
                interval
        );
    }
}
