package com.potner.diary.presentation;

import com.potner.config.OpenApiConfig;
import com.potner.diary.application.DiaryService;
import com.potner.diary.dto.DiaryDetailResponse;
import com.potner.diary.dto.DiaryListResponse;
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
 * 성장 일기 조회다.
 *
 * <p>작성 엔드포인트가 없다. 일기는 사용자가 쓰는 것이 아니라 매일 정해진 시각에 LLM 이 그날의
 * 센서·알림·사진을 근거로 쓴다. 생성은 {@code DiaryScheduler} 가 맡는다.
 *
 * <p>{@code LLM_API_KEY} 가 없으면 생성기가 no-op 구현으로 대체되어 일기가 한 건도 만들어지지
 * 않는다. 조회가 계속 비어 있으면 이 설정을 먼저 확인해야 한다.
 */
@RestController
@RequestMapping("/api/v1/plants/{plantId}/diaries")
@Tag(name = "성장 일기", description = "매일 자동으로 기록되는 성장 일기 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DiaryController {

    private final DiaryService diaryService;

    public DiaryController(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    @GetMapping
    @Operation(
            summary = "기간 내 일기 목록 조회",
            description = """
                    최신순이다. 사진 타임랩스와 달리 사용자는 최근 일기부터 읽는다.

                    본문은 담지 않는다. 잘라 보내면 앱이 다시 늘릴 수 없고 전부 보내면 한 달치가
                    한 응답에 실린다. 목록은 날짜와 제목으로 고르는 화면이고 본문은 상세에서 읽는다.

                    thumbnailUrl 은 그날 장치가 찍은 사진이다. 촬영이 없던 날은 null 이다.

                    from 과 to 는 서비스 타임존 기준 날짜(yyyy-MM-dd)이며 양쪽 모두 포함이다.
                    """
    )
    public DiaryListResponse getDiaries(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return diaryService.getDiaries(principal.userId(), plantId, from, to);
    }

    @GetMapping("/{diaryId}")
    @Operation(
            summary = "일기 상세 조회",
            description = """
                    본문과 그날 장치 사진을 함께 준다. 일기가 사진을 따로 갖지 않는 이유는 장치가
                    하루 한 장 찍어 날짜만으로 찾을 수 있기 때문이다. 촬영이 없던 날은 photo 가
                    null 이다.

                    상태 리포트는 여기 담지 않는다. 일기가 없는 날에도 점수는 있어야 하고 홈
                    화면도 오늘 상태를 쓰므로 별도 엔드포인트로 나간다.
                    """
    )
    public DiaryDetailResponse getDiary(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String plantId,
            @PathVariable String diaryId
    ) {
        return diaryService.getDiary(principal.userId(), plantId, diaryId);
    }
}
