package com.potner.light.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.light.application.DailyLightQueryService;
import com.potner.light.dto.DailyLightResponse;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plants/{plantId}/daily-light")
@Tag(name = "일일 광량", description = "하루 누적 광량 및 일조 시간 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DailyLightController {

    private final DailyLightQueryService dailyLightQueryService;

    public DailyLightController(DailyLightQueryService dailyLightQueryService) {
        this.dailyLightQueryService = dailyLightQueryService;
    }

    @GetMapping
    @Operation(
            summary = "일일 광량 진행률 및 확정 이력 조회",
            description = """
                    today 는 진행 중인 오늘의 누적값이며 판정 결과를 담지 않는다. 앱은 progressPct 로
                    진행률을 보여준다. history 는 하루가 마감되어 판정이 끝난 날들이며 오늘은 포함하지 않는다.

                    하루의 경계는 서비스 타임존 기준이다. 응답의 zoneOffset 이 그 기준이고
                    lightDate 는 해당 타임존의 날짜다.

                    조도는 순간값으로 판정하지 않는다. 밤에는 0 lux 가 정상이고 생육 기준이
                    하루 누적 광량과 일조 시간으로 표현되어 있기 때문이다.
                    """
    )
    public DailyLightResponse getDailyLight(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestParam(defaultValue = "7") int days
    ) {
        return dailyLightQueryService.getDailyLight(principal.userId(), plantId, days);
    }
}
