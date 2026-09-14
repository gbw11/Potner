package com.potner.happiness.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.happiness.application.StatusReportService;
import com.potner.happiness.dto.StatusReportResponse;
import com.potner.security.AuthenticatedUser;
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

import java.time.LocalDate;

/**
 * 하루를 돌아보는 상태 리포트다. 일기 상세 화면이 쓴다.
 *
 * <p>일기 응답에 싣지 않는다. 일기가 없는 날에도 리포트는 있어야 하기 때문이다. 사용자가 달력에서
 * 일기 없는 날을 눌렀을 때 그날 상태를 못 보여주면 화면이 빈다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/status-report")
@Tag(name = "행복도", description = "현재 상태 등급과 일별 상태 리포트 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class StatusReportController {

    private final StatusReportService statusReportService;

    public StatusReportController(StatusReportService statusReportService) {
        this.statusReportService = statusReportService;
    }

    @GetMapping
    @Operation(
            summary = "일별 상태 리포트 조회",
            description = """
                    그날 하루의 행복 점수와 일조량, 급수량을 준다. 일기 상세의 "상태 리포트"
                    영역이다.

                    date 는 서비스 타임존 기준 날짜(yyyy-MM-dd)다.

                    happinessScore 는 0~100 이며 100 에서 시작해 그날 열린 이상 알림 한 지표당
                    깎는다. 그날 꽃이 폈으면 더한다. 감점·가점 폭은 서버 설정이라 바뀔 수 있으므로
                    앱이 값을 계산하지 말고 그대로 보여줘야 한다.

                    adjustments 가 점수가 100 에서 움직인 이유다. 사용자가 "왜 95점인지" 를 물을 때
                    앱이 답할 수 있게 하려는 것이다.

                    누적 광량과 일조 시간 이상도 알림으로 기록되므로 그 감점에 포함된다. 따로
                    또 깎지 않는다.

                    happinessScore 가 null 이면 그날 측정값이 아예 없어 판정하지 못한 것이다.
                    0 점이 아니다. "최악의 하루" 와 "기록이 없는 하루" 는 다르다.

                    lightHours 는 임계 조도 이상을 받은 실측 시간이다. 광량은 하루가 끝난 뒤 새벽에
                    확정되므로 오늘 것은 null 이다.

                    wateredMl 은 그날 실제로 나간 급수량의 합이다(device_command.dispensed_ml).
                    급수 기록이 없으면 null 이며 0 과 구분된다 — 0 은 급수를 시도했으나 한 방울도
                    안 나간 경우다.
                    """
    )
    public StatusReportResponse getStatusReport(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return statusReportService.getReport(principal.userId(), plantId, date);
    }
}
