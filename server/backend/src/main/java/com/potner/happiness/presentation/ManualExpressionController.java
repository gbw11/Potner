package com.potner.happiness.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.happiness.application.ManualExpressionService;
import com.potner.happiness.dto.PublishExpressionRequest;
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
 * 표정 수동 발행이다. 시연과 진단용이며 평소 표정은 서버가 주기적으로 판정해 보낸다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/expression")
@Tag(name = "행복도", description = "로봇 디스플레이 표정 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ManualExpressionController {

    private final ManualExpressionService manualExpressionService;

    public ManualExpressionController(ManualExpressionService manualExpressionService) {
        this.manualExpressionService = manualExpressionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "표정 수동 발행",
            description = """
                    expression 은 VERY_HAPPY / HAPPY / NEUTRAL / SAD 다. reason 은 말풍선 문구를
                    고르는 힌트이며 생략하면 NONE 이다.

                    **다음 주기 발행이 덮어쓴다.** 표정은 서버가
                    potner.happiness.publish-interval-seconds(기본 30초) 마다 다시 판정해 밀어
                    주므로, 손으로 보낸 값은 한 주기만 유지된다. 로봇 디스플레이가 실제로
                    반응하는지 확인하는 용도다.

                    발행만 하고 회신을 기다리지 않으므로 202 다. 표정에는 result 회신 계약이
                    없어 수행 여부를 서버가 확인할 방법이 없다 - 로봇 화면을 눈으로 봐야 한다.

                    식물에 배정된 로봇이 없으면 404 (PLANT_ASSIGNMENT_NOT_FOUND), 그 로봇에
                    디스플레이 장치가 등록되어 있지 않으면 404 (COMMAND_DEVICE_NOT_FOUND) 다.

                    브로커가 꺼진 환경에서는 발행기가 no-op 이라 202 를 받고도 아무것도 나가지
                    않는다. 서버 로그의 "Manual expression published" 로 확인한다.
                    """
    )
    public void publish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @Valid @RequestBody PublishExpressionRequest request
    ) {
        manualExpressionService.publish(principal.userId(), plantId, request);
    }
}
