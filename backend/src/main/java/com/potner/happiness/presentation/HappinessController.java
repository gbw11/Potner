package com.potner.happiness.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.happiness.application.HappinessService;
import com.potner.happiness.dto.HappinessResponse;
import com.potner.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 표정 조회다.
 *
 * <p>앱이 쓰는 화면이 없다. 표정은 로봇 디스플레이에만 나가고 서버가 MQTT 로 밀어 준다.
 * 이 엔드포인트는 로봇을 켜지 않고 판정을 확인하기 위한 통로이며, 통합 테스트도 이걸 쓴다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/happiness")
@Tag(name = "행복도", description = "로봇 디스플레이 표정 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class HappinessController {

    private final HappinessService happinessService;

    public HappinessController(HappinessService happinessService) {
        this.happinessService = happinessService;
    }

    @GetMapping
    @Operation(
            summary = "현재 상태 조회 (홈 화면)",
            description = """
                    지금 상태를 등급과 두 줄 문구로 준다. 홈 화면의 "로지의 상태" 영역이다.

                    grade 는 GOOD, FAIR, POOR, UNKNOWN 이다. 기준을 벗어난 센서 지표 수로 정하며
                    0개면 GOOD, 1개면 FAIR, 2개 이상이면 POOR 다. 신선한 측정값이 하나도 없거나
                    생육 기준이 없으면 UNKNOWN 이다 — GOOD 으로 처리하면 기기가 꺼져 있는 동안
                    "아주 좋아요" 를 보여주게 된다.

                    headline 과 detail 이 화면의 큰 글씨·작은 글씨다. 서버가 문구를 만들지만
                    abnormalMetrics 도 함께 주므로 앱이 자체 문구로 바꿀 수 있다.

                    abnormalMetrics 에는 토양 수분·온도·습도만 들어온다. 조도는 밤에 0 lux 가
                    정상이라 순간값으로 판정하지 않으며, 광량은 일별 점수에서 다룬다.

                    expression 은 로봇 디스플레이용이다. 앱은 쓰지 않는다. 등급과 다른 값이 나올
                    수 있다 — 급수 중이면 표정은 VERY_HAPPY 지만 온도가 나쁘면 등급은 그대로
                    나쁘다. 표정은 감정을 전하고 등급은 확인할 것을 알린다.

                    점수는 여기 없다. 점수는 하루를 돌아보는 값이라 status-report 로 나간다.
                    """
    )
    public HappinessResponse getHappiness(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        return happinessService.getHappiness(principal.userId(), plantId);
    }
}
