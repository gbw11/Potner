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
            summary = "표정 조회",
            description = """
                    로봇 디스플레이에 그릴 표정을 반환한다. 앱 화면용이 아니라 판정 확인용이다.

                    expression 은 VERY_HAPPY, HAPPY, NEUTRAL, SAD 넷이다. 온·습도가 baseline 을
                    정하고 행동이 그 위를 덮으며, 행동 쪽이 이긴다.

                    baseline 은 부스트가 걷힌 뒤 돌아갈 표정이다. 급수 중이라 VERY_HAPPY 인데
                    baseline 이 SAD 이면 물을 다 마신 뒤 우울로 떨어진다는 뜻이다.

                    reason 은 서버가 문구를 만들지 않으므로 표시할 말은 받는 쪽이 고른다.
                    측정값이 신선도 기준을 넘겨 오래됐으면 NEUTRAL / NONE 이다.
                    """
    )
    public HappinessResponse getHappiness(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId
    ) {
        return happinessService.getHappiness(principal.userId(), plantId);
    }
}
